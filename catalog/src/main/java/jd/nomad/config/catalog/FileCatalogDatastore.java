package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jd.nomad.config.CatalogConfig;
import jd.nomad.config.IndexerConfig;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import lombok.RequiredArgsConstructor;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

@ApplicationScoped
@LookupIfProperty(name = "indexer.catalog.source", stringValue = "file")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class FileCatalogDatastore implements CatalogDatastore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexerConfig indexerConfig;
    private final CatalogSnapshotBuilder snapshotBuilder;

    @Override
    public CatalogSnapshot start(BiConsumer<String, JsonNode> mappingChangeListener) throws IOException {
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
        }

        return new CatalogSnapshot(Map.copyOf(mappings), Map.copyOf(virtualMappings), Map.copyOf(backendsByMaterialType));
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
}
