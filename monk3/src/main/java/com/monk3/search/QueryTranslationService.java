package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.Aggregation;
import com.monk3.model.SearchQueryRequest;
import com.monk3.routing.QueryAnalysis;
import com.monk3.routing.QueryAnalyzer;
import com.monk3.routing.RoutingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.BackendConfig;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * Routes each requested material type to a backend and groups the request per backend,
     * preserving request order.
     */
    public List<BackendTarget> resolveTargets(SearchQueryRequest request) {
        QueryAnalysis analysis = QueryAnalyzer.analyze(request.query());
        Map<String, List<String>> materialTypesByBackend = new LinkedHashMap<>();
        for (String materialType : request.materialTypes()) {
            String backendName = routingEngine.resolve(
                    catalogService.backendForMaterialType(materialType),
                    catalogService.routingRulesForMaterialType(materialType),
                    analysis);
            materialTypesByBackend.computeIfAbsent(backendName, k -> new ArrayList<>()).add(materialType);
        }
        return materialTypesByBackend.entrySet().stream()
                .map(entry -> resolveTarget(entry.getKey(), entry.getValue(), request))
                .toList();
    }

    private BackendTarget resolveTarget(String backendName, List<String> materialTypes, SearchQueryRequest request) {
        BackendConfig backendConfig;
        try {
            backendConfig = catalogService.backendConfig(backendName);
        } catch (IllegalStateException e) {
            throw new QueryTranslationException("No configured search backend named '" + backendName + "'");
        }
        SearchQueryRequest groupedRequest = new SearchQueryRequest(
                request.id(), request.name(), materialTypes, request.query());
        return new BackendTarget(backendName, backendConfig, SearchEngine.of(backendConfig.engine()), groupedRequest);
    }

    public ObjectNode translate(SearchEngine searchEngine, SearchQueryRequest request) {
        List<JsonNode> materialQueries = request.materialTypes().stream()
                .<JsonNode>map(materialType -> translateMaterialType(searchEngine, request, materialType))
                .toList();
        return (ObjectNode) QueryJson.shouldOrSingle(searchEngine, materialQueries);
    }

    private ObjectNode translateMaterialType(
            SearchEngine searchEngine,
            SearchQueryRequest request,
            String materialType
    ) {
        JsonNode query = request.query().translate(searchEngine, contextFor(materialType));
        JsonNode filter = searchEngine == SearchEngine.ELASTICSEARCH
                ? JsonNodeFactory.instance.objectNode()
                        .set("term", JsonNodeFactory.instance.objectNode().put(config.materialTypeField(), materialType))
                : QueryJson.solrFieldQuery(config.materialTypeField(), materialType);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        bool.putArray("filter").add(filter);
        bool.putArray("must").add(query);
        return root;
    }

    public ObjectNode translateAggregations(
            SearchEngine searchEngine,
            List<String> materialTypes,
            Map<String, Aggregation> aggs
    ) {
        AggregationContext context = aggregationContext(materialTypes);
        ObjectNode translated = JsonNodeFactory.instance.objectNode();
        aggs.forEach((name, aggregation) -> translated.set(name, aggregation.translate(searchEngine, context)));
        return translated;
    }

    private AggregationContext aggregationContext(List<String> materialTypes) {
        Map<String, QueryParseContext> contexts = new LinkedHashMap<>();
        for (String materialType : materialTypes) {
            contexts.put(materialType, contextFor(materialType));
        }
        return new AggregationContext(contexts);
    }

    private QueryParseContext contextFor(String materialType) {
        return QueryParseContext.root(
                catalogService.mappingForMaterialType(materialType),
                catalogService.virtualMappingForMaterialType(materialType).orElse(null),
                virtualFieldExpander);
    }

    public record BackendTarget(
            String name,
            BackendConfig backend,
            SearchEngine engine,
            SearchQueryRequest request
    ) {
        public List<String> materialTypes() {
            return request.materialTypes();
        }
    }
}
