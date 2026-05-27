package jd.nomad.config;

import java.util.Map;

public record CatalogConfig(Map<String, MappingEntry> mappings) {

    public record MappingEntry(String physical, String virtual) {}
}
