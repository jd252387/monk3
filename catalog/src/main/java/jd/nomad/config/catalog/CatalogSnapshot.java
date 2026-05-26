package jd.nomad.config.catalog;

import java.util.Map;
import jd.nomad.mapping.SearchMapping;

public record CatalogSnapshot(Map<String, SearchMapping> mappings) {
    public enum ConfigType {
        MAPPINGS
    }
}
