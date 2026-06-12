package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jd.nomad.config.IndexerConfig;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.SearchMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
@LookupIfProperty(name = "indexer.catalog.source", stringValue = "ETCD")
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class EtcdCatalogDatastore implements CatalogDatastore {
    private final IndexerConfig indexerConfig;
    private final CatalogSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Client etcdClient;
    private CatalogUpdateSink sink = NoopSink.INSTANCE;

    @Override
    public CatalogSnapshot start(CatalogUpdateSink sink) throws IOException {
        this.sink = sink;
        this.etcdClient = buildEtcd();

        Map<String, String> keys = indexerConfig.catalog().etcd().mappings();
        Map<String, CompletableFuture<GetResponse>> pendingFetches = new LinkedHashMap<>();
        keys.forEach((materialType, key) -> pendingFetches.put(materialType, fetchFromEtcd(key)));

        Map<String, SearchMapping> mappings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String materialType = entry.getKey();
            String key = entry.getValue();
            JsonNode node = objectMapper.readTree(awaitEtcdValue(pendingFetches.get(materialType), key));
            mappings.put(materialType, snapshotBuilder.parseMapping(materialType, node));
            registerWatcher(this.etcdClient, materialType, key);
        }

        Map<String, BackendConfig> backends = indexerConfig.catalog().etcd().backends()
                .map(key -> {
                    try {
                        Map<String, BackendConfig> initial = readBackendsFromEtcd(key);
                        registerBackendsWatcher(this.etcdClient, key);
                        return initial;
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load backends from etcd key: " + key, e);
                    }
                })
                .orElse(Map.of());

        return new CatalogSnapshot(Map.copyOf(mappings), Map.of(), Map.of(), Map.of(), backends);
    }

    public Client buildEtcd() {
        IndexerConfig.EtcdSource etcd = indexerConfig.catalog().etcd();

        return Client.builder()
                .endpoints(etcd.endpoints().stream().map(java.net.URI::create).toArray(java.net.URI[]::new))
                .build();
    }

    private void registerWatcher(Client client, String materialType, String key) {
        Watch.Listener watchListener = Watch.listener(
                response -> response.getEvents().forEach(event -> handleEvent(event, materialType, key)),
                throwable -> log.atError()
                        .setCause(throwable)
                        .addArgument(key)
                        .log("Watcher for key {} terminated. Retaining previous configuration"),
                () -> log.atDebug().addArgument(key).log("Watcher for key {} closed"));

        client.getWatchClient()
                .watch(ByteSequence.from(key, StandardCharsets.UTF_8), WatchOption.DEFAULT, watchListener);
    }

    private void registerBackendsWatcher(Client client, String key) {
        Watch.Listener watchListener = Watch.listener(
                response -> response.getEvents().forEach(event -> handleBackendsEvent(event, key)),
                throwable -> log.atError()
                        .setCause(throwable)
                        .addArgument(key)
                        .log("Backends watcher for key {} terminated. Retaining previous configuration"),
                () -> log.atDebug().addArgument(key).log("Backends watcher for key {} closed"));

        client.getWatchClient()
                .watch(ByteSequence.from(key, StandardCharsets.UTF_8), WatchOption.DEFAULT, watchListener);
    }

    private void handleEvent(WatchEvent event, String materialType, String key) {
        switch (event.getEventType()) {
            case PUT -> {
                try {
                    JsonNode newNode = objectMapper.readTree(event.getKeyValue().getValue().getBytes());
                    sink.updateMapping(materialType, newNode);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to parse updated mapping for key " + key, e);
                }
            }
            case DELETE -> log.atWarn()
                    .addArgument(key)
                    .log("Configuration key {} removed from etcd; keeping previous configuration");
            default -> log.atDebug()
                    .addArgument(event.getEventType())
                    .addArgument(key)
                    .log("Ignoring etcd event {} for key {}");
        }
    }

    private void handleBackendsEvent(WatchEvent event, String key) {
        switch (event.getEventType()) {
            case PUT -> {
                try {
                    Map<String, BackendConfig> updated = parseBackends(event.getKeyValue().getValue().getBytes());
                    sink.updateBackends(updated);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to parse updated backends for key " + key, e);
                }
            }
            case DELETE -> log.atWarn()
                    .addArgument(key)
                    .log("Backends key {} removed from etcd; keeping previous configuration");
            default -> log.atDebug()
                    .addArgument(event.getEventType())
                    .addArgument(key)
                    .log("Ignoring etcd event {} for backends key {}");
        }
    }

    private Map<String, BackendConfig> readBackendsFromEtcd(String key) throws IOException {
        return parseBackends(readBytesFromEtcd(key));
    }

    private Map<String, BackendConfig> parseBackends(byte[] bytes) throws IOException {
        BackendsFile file = objectMapper.readValue(bytes, BackendsFile.class);
        return Map.copyOf(file.backends());
    }

    private byte[] readBytesFromEtcd(String key) {
        return awaitEtcdValue(fetchFromEtcd(key), key);
    }

    private CompletableFuture<GetResponse> fetchFromEtcd(String key) {
        return this.etcdClient.getKVClient().get(ByteSequence.from(key, StandardCharsets.UTF_8));
    }

    private static byte[] awaitEtcdValue(CompletableFuture<GetResponse> pending, String key) {
        try {
            GetResponse response = pending.get();
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
        public void updateMapping(String materialType, JsonNode node) {
        }

        @Override
        public void updateBackends(Map<String, BackendConfig> backends) {
        }

        @Override
        public void replaceSnapshot(CatalogSnapshot snapshot) {
        }
    }
}
