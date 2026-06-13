package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.Aggregation;
import com.monk3.model.QueryNode;
import com.monk3.model.SearchQueryRequest;
import com.monk3.routing.QueryAnalysis;
import com.monk3.routing.QueryAnalyzer;
import com.monk3.routing.RoutingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.BackendConfig;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@RequiredArgsConstructor
public class QueryTranslationService {
    private final ConfigurationCatalogService catalogService;
    private final SearchMappingConfig config;
    private final VirtualFieldExpander virtualFieldExpander;
    private final RoutingEngine routingEngine;

    /**
     * Routes each requested material type to a backend. Since a backend has exactly one mapping,
     * each material type yields its own target; request order is preserved.
     */
    public List<BackendTarget> resolveTargets(SearchQueryRequest request) {
        QueryAnalysis analysis = QueryAnalyzer.analyze(request.query());
        return request.materialTypes().stream()
                .map(materialType -> resolveTarget(materialType, request.query(), analysis))
                .toList();
    }

    private BackendTarget resolveTarget(String materialType, QueryNode query, QueryAnalysis analysis) {
        String backendName = routingEngine.resolve(
                catalogService.backendForMaterialType(materialType),
                catalogService.routingRulesForMaterialType(materialType),
                analysis);
        BackendConfig backendConfig;
        try {
            backendConfig = catalogService.backendConfig(backendName);
        } catch (IllegalStateException e) {
            throw new QueryTranslationException("No configured search backend named '" + backendName + "'");
        }
        return new BackendTarget(backendName, materialType, backendConfig, SearchEngine.of(backendConfig.engine()), query);
    }

    public ObjectNode translate(BackendTarget target) {
        SearchEngine engine = target.engine();
        JsonNode query = target.query().translate(engine, contextForBackend(target.name()));
        JsonNode filter = engine == SearchEngine.ELASTICSEARCH
                ? JsonNodeFactory.instance.objectNode()
                        .set("term", JsonNodeFactory.instance.objectNode().put(config.materialTypeField(), target.materialType()))
                : QueryJson.solrFieldQuery(config.materialTypeField(), target.materialType());
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        bool.putArray("filter").add(filter);
        bool.putArray("must").add(query);
        return root;
    }

    public ObjectNode translateAggregations(BackendTarget target, Map<String, Aggregation> aggs) {
        AggregationContext context = new AggregationContext(
                Map.of(target.materialType(), contextForBackend(target.name())));
        ObjectNode translated = JsonNodeFactory.instance.objectNode();
        aggs.forEach((name, aggregation) -> translated.set(name, aggregation.translate(target.engine(), context)));
        return translated;
    }

    private QueryParseContext contextForBackend(String backend) {
        return QueryParseContext.root(
                catalogService.mappingForBackend(backend),
                catalogService.virtualMappingForBackend(backend).orElse(null),
                virtualFieldExpander);
    }

    public record BackendTarget(
            String name,
            String materialType,
            BackendConfig backend,
            SearchEngine engine,
            QueryNode query
    ) {
    }
}
