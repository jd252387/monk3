package jd.nomad.config;

import jd.nomad.routing.RoutingRule;

import java.util.List;
import java.util.Map;

public record CatalogConfig(Map<String, MappingEntry> mappings) {

    public record MappingEntry(String physical, String virtual, String backend, List<RoutingRule> routing) {}
}
