package jd.nomad.config;

import com.fasterxml.jackson.databind.JsonNode;
import jd.nomad.routing.RoutingRule;

import java.util.List;
import java.util.Map;

public record CatalogConfig(Map<String, MappingEntry> mappings) {

    public record MappingEntry(String backend, List<RoutingRule> routing, JsonNode filter) {}
}
