package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.stream.Collectors;

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
                .collect(Collectors.groupingBy(BackendTarget::name))
                .values().stream()
                .map(group -> new BackendTarget(
                        group.getFirst().name(),
                        group.stream().flatMap(target -> target.materialTypes().stream()).toList(),
                        group.getFirst().backend(),
                        group.getFirst().engine(),
                        group.getFirst().query()))
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
        return new BackendTarget(backendName, List.of(materialType), backendConfig, SearchEngine.of(backendConfig.engine()), query);
    }

    public ObjectNode translate(BackendTarget target) {
        SearchEngine engine = target.engine();
        JsonNode query = target.query().translate(engine, contextForBackend(target.name()));
        List<String> materialTypes = target.materialTypes();
        JsonNode filter = engine == SearchEngine.ELASTICSEARCH
                ? materialTypeTerms(materialTypes)
                : materialTypeSolrFilter(materialTypes);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        bool.putArray("filter").add(filter);
        bool.putArray("must").add(query);
        return root;
    }

    private ObjectNode materialTypeTerms(List<String> materialTypes) {
        ObjectNode termNode = JsonNodeFactory.instance.objectNode();
        ArrayNode values = termNode.putObject("terms").putArray(config.materialTypeField());
        materialTypes.forEach(values::add);
        return termNode;
    }

    private JsonNode materialTypeSolrFilter(List<String> materialTypes) {
        return QueryJson.shouldOrSingle(SearchEngine.SOLR, materialTypes.stream()
                .<JsonNode>map(materialType -> QueryJson.solrFieldQuery(config.materialTypeField(), materialType))
                .toList());
    }

    public AggregationTranslation translateAggregations(BackendTarget target, Map<String, Aggregation> aggs) {
        QueryParseContext context = contextForBackend(target.name());
        Map<String, QueryParseContext> contextsByMaterialType = target.materialTypes().stream()
                .collect(Collectors.toMap(mt -> mt, mt -> context));
        ObjectNode aggregations = JsonNodeFactory.instance.objectNode();
        ObjectNode namedQueries = JsonNodeFactory.instance.objectNode();
        aggs.forEach((name, aggregation) -> {
            AggregationContext aggContext = new AggregationContext(contextsByMaterialType, name, namedQueries);
            aggregations.set(name, aggregation.translate(target.engine(), aggContext));
        });
        return new AggregationTranslation(aggregations, namedQueries);
    }

    private QueryParseContext contextForBackend(String backend) {
        return QueryParseContext.root(
                catalogService.mappingForBackend(backend),
                catalogService.virtualMappingForBackend(backend).orElse(null),
                virtualFieldExpander);
    }

    public record BackendTarget(
            String name,
            List<String> materialTypes,
            BackendConfig backend,
            SearchEngine engine,
            QueryNode query
    ) {
    }

    /**
     * Translated aggregations for a backend: the per-name facet/aggs node, plus any Solr root-level
     * {@code queries} contributed by query-based aggregations (empty for Elasticsearch).
     */
    public record AggregationTranslation(ObjectNode aggregations, ObjectNode namedQueries) {
    }
}
