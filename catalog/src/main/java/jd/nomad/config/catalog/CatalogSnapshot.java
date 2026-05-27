package jd.nomad.config.catalog;

import java.util.Map;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;

public record CatalogSnapshot(
        Map<String, SearchMapping> mappings,
        Map<String, VirtualMapping> virtualMappings,
        Map<String, String> backendsByMaterialType
) {
    public enum ConfigType {
        MAPPINGS
    }
}
