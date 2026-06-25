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
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@LookupIfProperty(name = "indexer.catalog.source", stringValue = "FILE")
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class FileCatalogDatastore implements CatalogDatastore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexerConfig indexerConfig;
    private final CatalogSnapshotBuilder snapshotBuilder;

    private CatalogUpdateSink sink;
    private FileSystemManager fileSystemManager;
    private DefaultFileMonitor monitor;
    private final Set<String> watchedUris = new LinkedHashSet<>();

    @Override
    public CatalogSnapshot start(CatalogUpdateSink sink) throws IOException {
        this.sink = sink;
        try {
            this.fileSystemManager = VFS.getManager();
        } catch (FileSystemException e) {
            throw new IOException("Failed to acquire VFS file system manager", e);
        }

        Set<String> refs = new LinkedHashSet<>();
        CatalogSnapshot snapshot = buildSnapshot(refs);

        monitor = new DefaultFileMonitor(new FileListener() {
            @Override
            public void fileChanged(FileChangeEvent event) {
                reload();
            }

            @Override
            public void fileCreated(FileChangeEvent event) {
                reload();
            }

            @Override
            public void fileDeleted(FileChangeEvent event) {
                reload();
            }
        });
        monitor.setRecursive(false);
        for (String uri : refs) {
            monitor.addFile(fileSystemManager.resolveFile(uri));
        }
        watchedUris.addAll(refs);
        monitor.start();

        return snapshot;
    }

    @PreDestroy
    void stop() {
        if (monitor != null) {
            monitor.stop();
        }
    }

    /**
     * Re-reads the catalog and every mapping it references, replacing the live snapshot. Runs on the file
     * monitor's callback thread, so it reconciles the watch set in place (add/remove) rather than restarting
     * the monitor, which would deadlock joining its own thread. A failed reload (e.g. a mid-save or invalid
     * edit) keeps the previous configuration.
     */
    private synchronized void reload() {
        try {
            Set<String> newRefs = new LinkedHashSet<>();
            CatalogSnapshot snapshot = buildSnapshot(newRefs);
            reconcileWatchedFiles(newRefs);
            sink.replaceSnapshot(snapshot);
        } catch (Exception e) {
            log.atError()
                    .setCause(e)
                    .log("Failed to reload catalog configuration; retaining previous configuration");
        }
    }

    private CatalogSnapshot buildSnapshot(Set<String> outRefs) throws IOException {
        String configPath = indexerConfig.catalog().file().config()
                .orElseThrow(() -> new IllegalStateException(
                        "indexer.catalog.file.config must be set when indexer.catalog.source=FILE"));
        outRefs.add(resolveLocation(configPath));
        JsonNode configNode = readJson(configPath);
        CatalogConfig catalogConfig = objectMapper.treeToValue(configNode, CatalogConfig.class);

        Map<String, String> backendsByMaterialType = new LinkedHashMap<>();
        Map<String, List<RoutingRule>> routingRulesByMaterialType = new LinkedHashMap<>();
        Map<String, JsonNode> filtersByMaterialType = new LinkedHashMap<>();
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
            if (mappingEntry.filter() != null && !mappingEntry.filter().isNull()) {
                filtersByMaterialType.put(materialType, mappingEntry.filter());
            }
            materialTypeByBackend.put(mappingEntry.backend(), materialType);
            rules.forEach(rule -> materialTypeByBackend.put(rule.backend(), materialType));
        }

        Map<String, BackendConfig> backends = Map.of();
        if (indexerConfig.catalog().file().backends().isPresent()) {
            String backendsPath = indexerConfig.catalog().file().backends().get();
            outRefs.add(resolveLocation(backendsPath));
            backends = readBackends(backendsPath);
        }

        Map<String, SearchMapping> mappingsByBackend = new LinkedHashMap<>();
        Map<String, VirtualMapping> virtualMappingsByBackend = new LinkedHashMap<>();
        for (Map.Entry<String, BackendConfig> entry : backends.entrySet()) {
            String backendName = entry.getKey();
            BackendConfig backend = entry.getValue();
            // Connection-only backends (e.g. clustered sinks declared just for their zk/chroot/hosts) carry
            // no mapping of their own; only backends that declare a physical mapping are loaded here.
            if (backend.physical() == null || backend.physical().isBlank()) {
                continue;
            }
            String label = materialTypeByBackend.getOrDefault(backendName, backendName);
            outRefs.add(resolveLocation(backend.physical()));
            mappingsByBackend.put(backendName, snapshotBuilder.parseMapping(label, readJson(backend.physical())));
            if (backend.virtual() != null && !backend.virtual().isBlank()) {
                outRefs.add(resolveLocation(backend.virtual()));
                virtualMappingsByBackend.put(backendName, snapshotBuilder.parseVirtualMapping(label, readJson(backend.virtual())));
            }
        }

        for (String backendName : materialTypeByBackend.keySet()) {
            if (!mappingsByBackend.containsKey(backendName)) {
                throw new IllegalStateException(
                        "Catalog references backend '" + backendName + "' which is not configured with a physical mapping");
            }
        }

        Map<String, DatasourceDescriptor> datasources = Map.of();
        if (indexerConfig.catalog().file().datasources().isPresent()) {
            String datasourcesPath = indexerConfig.catalog().file().datasources().get();
            outRefs.add(resolveLocation(datasourcesPath));
            datasources = snapshotBuilder.parseDatasources(readJson(datasourcesPath));
        }

        return new CatalogSnapshot(
                Map.copyOf(mappingsByBackend),
                Map.copyOf(virtualMappingsByBackend),
                Map.copyOf(backendsByMaterialType),
                Map.copyOf(routingRulesByMaterialType),
                Map.copyOf(filtersByMaterialType),
                backends,
                datasources);
    }

    private void reconcileWatchedFiles(Set<String> newRefs) throws FileSystemException {
        for (String uri : newRefs) {
            if (!watchedUris.contains(uri)) {
                monitor.addFile(fileSystemManager.resolveFile(uri));
            }
        }
        for (String uri : List.copyOf(watchedUris)) {
            if (!newRefs.contains(uri)) {
                monitor.removeFile(fileSystemManager.resolveFile(uri));
            }
        }
        watchedUris.clear();
        watchedUris.addAll(newRefs);
    }

    private Map<String, BackendConfig> readBackends(String location) throws IOException {
        FileObject file = fileSystemManager.resolveFile(resolveLocation(location));
        try (InputStream stream = file.getContent().getInputStream()) {
            BackendsFile parsed = objectMapper.readValue(stream, BackendsFile.class);
            return Map.copyOf(parsed.backends());
        }
    }

    private JsonNode readJson(String location) throws IOException {
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
