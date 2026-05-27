package jd.nomad.config.catalog;

import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import jd.nomad.routing.RoutingRule;

import java.util.List;
import java.util.Map;

public record CatalogSnapshot(
        Map<String, SearchMapping> mappings,
        Map<String, VirtualMapping> virtualMappings,
        Map<String, String> backendsByMaterialType,
        Map<String, List<RoutingRule>> routingRulesByMaterialType
) {
    public enum ConfigType {
        MAPPINGS
    }
}
