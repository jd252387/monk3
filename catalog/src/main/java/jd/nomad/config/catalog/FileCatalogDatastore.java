package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.commons.vfs2.FileChangeEvent;
import org.apache.commons.vfs2.FileListener;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.impl.DefaultFileMonitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@ApplicationScoped
@LookupIfProperty(name = "indexer.catalog.source", stringValue = "file")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class FileCatalogDatastore implements CatalogDatastore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexerConfig indexerConfig;
    private final CatalogSnapshotBuilder snapshotBuilder;

    private DefaultFileMonitor backendsMonitor;

    @Override
    public CatalogSnapshot start(
            BiConsumer<String, JsonNode> mappingChangeListener,
            Consumer<Map<String, BackendConfig>> backendsChangeListener) throws IOException {
        FileSystemManager fileSystemManager;
        try {
            fileSystemManager = VFS.getManager();
        } catch (FileSystemException e) {
            throw new IOException("Failed to acquire VFS file system manager", e);
        }

        String configPath = indexerConfig.catalog().file().config();
        JsonNode configNode = readJson(fileSystemManager, configPath);
        CatalogConfig catalogConfig = objectMapper.treeToValue(configNode, CatalogConfig.class);

        Map<String, SearchMapping> mappings = new LinkedHashMap<>();
        Map<String, VirtualMapping> virtualMappings = new LinkedHashMap<>();
        Map<String, String> backendsByMaterialType = new LinkedHashMap<>();
        Map<String, List<RoutingRule>> routingRulesByMaterialType = new LinkedHashMap<>();
        for (Map.Entry<String, CatalogConfig.MappingEntry> entry : catalogConfig.mappings().entrySet()) {
            String materialType = entry.getKey();
            CatalogConfig.MappingEntry mappingEntry = entry.getValue();
            if (mappingEntry.backend() == null || mappingEntry.backend().isBlank()) {
                throw new IllegalStateException(
                        "Catalog entry for material type '" + materialType + "' does not specify a backend");
            }
            JsonNode node = readJson(fileSystemManager, mappingEntry.physical());
            mappings.put(materialType, snapshotBuilder.parseMapping(materialType, node));
            if (mappingEntry.virtual() != null) {
                JsonNode virtualNode = readJson(fileSystemManager, mappingEntry.virtual());
                virtualMappings.put(materialType, snapshotBuilder.parseVirtualMapping(materialType, virtualNode));
            }
            backendsByMaterialType.put(materialType, mappingEntry.backend());
            routingRulesByMaterialType.put(materialType,
                    mappingEntry.routing() != null ? List.copyOf(mappingEntry.routing()) : List.of());
        }

        Map<String, BackendConfig> backends = loadAndWatchBackends(
                fileSystemManager, backendsChangeListener);

        return new CatalogSnapshot(
                Map.copyOf(mappings),
                Map.copyOf(virtualMappings),
                Map.copyOf(backendsByMaterialType),
                Map.copyOf(routingRulesByMaterialType),
                backends);
    }

    @PreDestroy
    void stop() {
        if (backendsMonitor != null) {
            backendsMonitor.stop();
        }
    }

    private Map<String, BackendConfig> loadAndWatchBackends(
            FileSystemManager fileSystemManager,
            Consumer<Map<String, BackendConfig>> backendsChangeListener) throws IOException {
        return indexerConfig.catalog().file().backends()
                .map(backendsPath -> {
                    try {
                        Map<String, BackendConfig> initial = readBackends(fileSystemManager, backendsPath);
                        watchBackendsFile(fileSystemManager, backendsPath, backendsChangeListener);
                        return initial;
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load backends file: " + backendsPath, e);
                    }
                })
                .orElse(Map.of());
    }

    private void watchBackendsFile(
            FileSystemManager fileSystemManager,
            String backendsPath,
            Consumer<Map<String, BackendConfig>> backendsChangeListener) throws IOException {
        FileObject backendsFile = fileSystemManager.resolveFile(resolveLocation(backendsPath));
        backendsMonitor = new DefaultFileMonitor(new FileListener() {
            @Override
            public void fileChanged(FileChangeEvent event) throws Exception {
                backendsChangeListener.accept(readBackends(fileSystemManager, backendsPath));
            }

            @Override
            public void fileCreated(FileChangeEvent event) throws Exception {
                fileChanged(event);
            }

            @Override
            public void fileDeleted(FileChangeEvent event) {
            }
        });
        backendsMonitor.setRecursive(false);
        backendsMonitor.addFile(backendsFile);
        backendsMonitor.start();
    }

    private Map<String, BackendConfig> readBackends(FileSystemManager fileSystemManager, String location)
            throws IOException {
        FileObject file = fileSystemManager.resolveFile(resolveLocation(location));
        try (InputStream stream = file.getContent().getInputStream()) {
            BackendsFile parsed = objectMapper.readValue(stream, BackendsFile.class);
            return Map.copyOf(parsed.backends());
        }
    }

    private JsonNode readJson(FileSystemManager fileSystemManager, String location) throws IOException {
        FileObject file = fileSystemManager.resolveFile(resolveLocation(location));
        try (InputStream stream = file.getContent().getInputStream()) {
            return objectMapper.readTree(stream);
        }
    }

    private static String resolveLocation(String location) {
        if (hasScheme(location)) {
            return location;
        }
        Path path = Paths.get(location).toAbsolutePath();
        return path.toUri().toString();
    }

    private static boolean hasScheme(String location) {
        int colon = location.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = location.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private record BackendsFile(Map<String, BackendConfig> backends) {}
}
