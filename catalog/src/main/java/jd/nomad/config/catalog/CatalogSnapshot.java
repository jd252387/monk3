package jd.nomad.config.catalog;

import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import jd.nomad.routing.RoutingRule;

import java.util.List;
import java.util.Map;

public record CatalogSnapshot(
        Map<String, SearchMapping> mappingsByBackend,
        Map<String, VirtualMapping> virtualMappingsByBackend,
        Map<String, String> backendsByMaterialType,
        Map<String, List<RoutingRule>> routingRulesByMaterialType,
        Map<String, BackendConfig> backends
) {
}
