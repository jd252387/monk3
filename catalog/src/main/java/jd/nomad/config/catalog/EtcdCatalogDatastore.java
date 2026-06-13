package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.WatchOption;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jd.nomad.config.CatalogConfig;
import jd.nomad.config.IndexerConfig;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import jd.nomad.routing.RoutingRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Etcd-backed catalog datastore. Mirrors {@link FileCatalogDatastore}: it reads a catalog document
 * (the same shape as the file source's {@code catalog.json}) whose mapping entries reference other
 * etcd keys for the physical/virtual mappings, plus an optional backends key. Every key that
 * contributes to the snapshot is watched; any change triggers a full rebuild, and a failed rebuild
 * (e.g. a malformed mid-edit value) retains the previous configuration.
 */
@ApplicationScoped
@LookupIfProperty(name = "indexer.catalog.source", stringValue = "ETCD")
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class EtcdCatalogDatastore implements CatalogDatastore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexerConfig indexerConfig;
    private final CatalogSnapshotBuilder snapshotBuilder;

    private Client etcdClient;
    private CatalogUpdateSink sink = NoopSink.INSTANCE;
    private final List<Watch.Watcher> watchers = new CopyOnWriteArrayList<>();
    private final Set<String> watchedKeys = new LinkedHashSet<>();

    /**
     * Single thread that runs reloads off the etcd/Vert.x event loop. Watch callbacks are dispatched
     * on the gRPC event loop thread; doing the blocking KV reads there would deadlock (the read's
     * future can only complete on that same thread) and stall every other watcher. Serializing
     * reloads here also means concurrent key changes never rebuild the snapshot in parallel.
     */
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "etcd-catalog-reload");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public synchronized CatalogSnapshot start(CatalogUpdateSink sink) throws IOException {
        this.sink = sink;
        this.etcdClient = buildEtcd();

        Set<String> refs = new LinkedHashSet<>();
        CatalogSnapshot snapshot = buildSnapshot(refs);
        refs.forEach(this::registerWatcher);
        watchedKeys.addAll(refs);
        return snapshot;
    }

    @PreDestroy
    void stop() {
        watchers.forEach(Watch.Watcher::close);
        watchers.clear();
        reloadExecutor.shutdownNow();
        if (etcdClient != null) {
            etcdClient.close();
        }
    }

    private Client buildEtcd() {
        IndexerConfig.EtcdSource etcd = indexerConfig.catalog().etcd();
        return Client.builder()
                .endpoints(etcd.endpoints().stream().map(java.net.URI::create).toArray(java.net.URI[]::new))
                .build();
    }

    /**
     * Re-reads the catalog and every key it references, replacing the live snapshot. A failed reload
     * (e.g. a mid-edit or invalid value) keeps the previous configuration. New keys discovered during
     * the rebuild (e.g. a freshly added material type's mapping) are added to the watch set.
     */
    private synchronized void reload() {
        try {
            Set<String> newRefs = new LinkedHashSet<>();
            CatalogSnapshot snapshot = buildSnapshot(newRefs);
            for (String key : newRefs) {
                if (watchedKeys.add(key)) {
                    registerWatcher(key);
                }
            }
            sink.replaceSnapshot(snapshot);
        } catch (Exception e) {
            log.atError()
                    .setCause(e)
                    .log("Failed to reload catalog configuration from etcd; retaining previous configuration");
        }
    }

    private CatalogSnapshot buildSnapshot(Set<String> outRefs) throws IOException {
        String catalogKey = indexerConfig.catalog().etcd().catalog()
                .orElseThrow(() -> new IllegalStateException(
                        "indexer.catalog.etcd.catalog must be set when indexer.catalog.source=ETCD"));
        outRefs.add(catalogKey);
        CatalogConfig catalogConfig = objectMapper.treeToValue(readJsonFromEtcd(catalogKey), CatalogConfig.class);

        Map<String, String> backendsByMaterialType = new LinkedHashMap<>();
        Map<String, List<RoutingRule>> routingRulesByMaterialType = new LinkedHashMap<>();
        Map<String, String> materialTypeByBackend = new LinkedHashMap<>();
        for (Map.Entry<String, CatalogConfig.MappingEntry> entry : catalogConfig.mappings().entrySet()) {
            String materialType = entry.getKey();
            CatalogConfig.MappingEntry mappingEntry = entry.getValue();
            if (mappingEntry.backend() == null || mappingEntry.backend().isBlank()) {
                throw new IllegalStateException(
                        "Catalog entry for material type '" + materialType + "' does not specify a backend");
            }
            List<RoutingRule> rules = mappingEntry.routing() != null ? List.copyOf(mappingEntry.routing()) : List.of();
            backendsByMaterialType.put(materialType, mappingEntry.backend());
            routingRulesByMaterialType.put(materialType, rules);
            materialTypeByBackend.put(mappingEntry.backend(), materialType);
            rules.forEach(rule -> materialTypeByBackend.put(rule.backend(), materialType));
        }

        Map<String, BackendConfig> backends = Map.of();
        if (indexerConfig.catalog().etcd().backends().isPresent()) {
            String backendsKey = indexerConfig.catalog().etcd().backends().get();
            outRefs.add(backendsKey);
            backends = readBackendsFromEtcd(backendsKey);
        }

        Map<String, SearchMapping> mappingsByBackend = new LinkedHashMap<>();
        Map<String, VirtualMapping> virtualMappingsByBackend = new LinkedHashMap<>();
        for (Map.Entry<String, BackendConfig> entry : backends.entrySet()) {
            String backendName = entry.getKey();
            BackendConfig backend = entry.getValue();
            String label = materialTypeByBackend.getOrDefault(backendName, backendName);
            if (backend.physical() == null || backend.physical().isBlank()) {
                throw new IllegalStateException("Backend '" + backendName + "' does not specify a physical mapping");
            }
            outRefs.add(backend.physical());
            mappingsByBackend.put(backendName, snapshotBuilder.parseMapping(label, readJsonFromEtcd(backend.physical())));
            if (backend.virtual() != null && !backend.virtual().isBlank()) {
                outRefs.add(backend.virtual());
                virtualMappingsByBackend.put(backendName, snapshotBuilder.parseVirtualMapping(label, readJsonFromEtcd(backend.virtual())));
            }
        }

        for (String backendName : materialTypeByBackend.keySet()) {
            if (!mappingsByBackend.containsKey(backendName)) {
                throw new IllegalStateException(
                        "Catalog references backend '" + backendName + "' which is not configured");
            }
        }

        return new CatalogSnapshot(
                Map.copyOf(mappingsByBackend),
                Map.copyOf(virtualMappingsByBackend),
                Map.copyOf(backendsByMaterialType),
                Map.copyOf(routingRulesByMaterialType),
                backends);
    }

    private void registerWatcher(String key) {
        Watch.Listener listener = Watch.listener(
                response -> {
                    if (!response.getEvents().isEmpty()) {
                        reloadExecutor.execute(this::reload);
                    }
                },
                throwable -> log.atError()
                        .setCause(throwable)
                        .addArgument(key)
                        .log("Watcher for key {} terminated; retaining previous configuration"),
                () -> log.atDebug().addArgument(key).log("Watcher for key {} closed"));

        watchers.add(etcdClient.getWatchClient()
                .watch(ByteSequence.from(key, StandardCharsets.UTF_8), WatchOption.DEFAULT, listener));
    }

    private Map<String, BackendConfig> readBackendsFromEtcd(String key) throws IOException {
        BackendsFile file = objectMapper.readValue(readBytesFromEtcd(key), BackendsFile.class);
        return Map.copyOf(file.backends());
    }

    private JsonNode readJsonFromEtcd(String key) throws IOException {
        return objectMapper.readTree(readBytesFromEtcd(key));
    }

    private byte[] readBytesFromEtcd(String key) {
        try {
            GetResponse response = etcdClient.getKVClient()
                    .get(ByteSequence.from(key, StandardCharsets.UTF_8))
                    .get();
            if (response.getKvs().isEmpty()) {
                throw new IllegalStateException("Etcd key " + key + " does not exist");
            }
            return response.getKvs().get(0).getValue().getBytes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading etcd key " + key, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to read etcd key " + key, e.getCause());
        }
    }

    private record BackendsFile(Map<String, BackendConfig> backends) {}

    private enum NoopSink implements CatalogUpdateSink {
        INSTANCE;

        @Override
        public void replaceSnapshot(CatalogSnapshot snapshot) {
        }
    }
}
