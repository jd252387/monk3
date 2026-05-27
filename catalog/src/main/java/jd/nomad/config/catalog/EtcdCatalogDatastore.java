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
import jd.nomad.mapping.SearchMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

@ApplicationScoped
@LookupIfProperty(name = "indexer.catalog.source", stringValue = "etcd")
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class EtcdCatalogDatastore implements CatalogDatastore {
    private final IndexerConfig indexerConfig;
    private final CatalogSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Client etcdClient;
    private BiConsumer<String, JsonNode> mappingChangeListener = (materialType, node) -> {};

    @Override
    public CatalogSnapshot start(BiConsumer<String, JsonNode> mappingChangeListener) throws IOException {
        this.mappingChangeListener = mappingChangeListener;
        this.etcdClient = buildEtcd();

        Map<String, String> keys = indexerConfig.catalog().etcd().mappings();
        Map<String, SearchMapping> mappings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String materialType = entry.getKey();
            String key = entry.getValue();
            JsonNode node = readJsonFromEtcd(key);
            mappings.put(materialType, snapshotBuilder.parseMapping(materialType, node));
            registerWatcher(this.etcdClient, materialType, key);
        }
        // TODO: etcd source has no per-material-type backend config yet; backend routing not supported
        return new CatalogSnapshot(Map.copyOf(mappings), Map.of(), Map.of(), Map.of());
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

    private void handleEvent(WatchEvent event, String materialType, String key) {
        switch (event.getEventType()) {
            case PUT -> {
                try {
                    JsonNode newNode = objectMapper.readTree(event.getKeyValue().getValue().getBytes());
                    mappingChangeListener.accept(materialType, newNode);
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

    private JsonNode readJsonFromEtcd(String key) throws IOException {
        try {
            GetResponse response = this.etcdClient
                    .getKVClient()
                    .get(ByteSequence.from(key, StandardCharsets.UTF_8))
                    .get();
            if (response.getKvs().isEmpty()) {
                throw new IllegalStateException("Etcd key " + key + " does not exist");
            }
            byte[] value = response.getKvs().get(0).getValue().getBytes();
            return objectMapper.readTree(value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading etcd key " + key, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to read etcd key " + key, e.getCause());
        }
    }
}
