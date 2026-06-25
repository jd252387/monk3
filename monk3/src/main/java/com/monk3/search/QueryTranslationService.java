package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.VapiConfig;
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
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
@RequiredArgsConstructor
public class QueryTranslationService {
    private final ConfigurationCatalogService catalogService;
    private final VapiConfig vapiConfig;
    private final VirtualFieldExpander virtualFieldExpander;
    private final RoutingEngine routingEngine;
    private final ObjectMapper objectMapper;
    private final EmbeddingClient embeddingClient;

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

    public QueryTranslation translate(BackendTarget target) {
        SearchEngine engine = target.engine();
        QueryParseContext context = contextForBackend(target.name());
        if (engine == SearchEngine.SOLR) {
            context = context.withSolrRootIdentifier(translateRootIdentifier(context, engine));
        }
        JsonNode query = target.query().translate(engine, context);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        List<JsonNode> filters = translateMappingFilters(target, engine, context);
        if (!filters.isEmpty()) {
            bool.putArray("filter").add(QueryJson.shouldOrSingle(engine, filters));
        }
        bool.putArray("must").add(query);
        return new QueryTranslation(root, context.solrNamedQueries());
    }

    /**
     * Translates each requested material type's optional {@code filter} (a DSL {@code QueryNode} declared
     * in {@code catalog.json}) into engine JSON. Material types declaring no filter contribute nothing; all
     * material types in a target share one backend, so the single per-backend {@code context} applies to each.
     */
    private List<JsonNode> translateMappingFilters(BackendTarget target, SearchEngine engine, QueryParseContext context) {
        return target.materialTypes().stream()
                .map(catalogService::filterForMaterialType)
                .flatMap(Optional::stream)
                .<JsonNode>map(filter -> objectMapper.convertValue(filter, QueryNode.class).translate(engine, context))
                .toList();
    }

    /**
     * Translates the root document's optional {@code identifier} DSL query into engine JSON, used as the
     * Solr {@code {!parent}} block mask for root-level nested queries. Returns {@code null} when none is declared.
     */
    private JsonNode translateRootIdentifier(QueryParseContext context, SearchEngine engine) {
        return context.mapping().root().identifier()
                .map(identifier -> objectMapper.convertValue(identifier, QueryNode.class).translate(engine, context))
                .orElse(null);
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
                virtualFieldExpander,
                vapiConfig.vapi(),
                embeddingClient);
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

    /**
     * Translated main query for a backend: the query node, plus any Solr root-level {@code queries}
     * contributed by block-mask root identifiers (empty for Elasticsearch).
     */
    public record QueryTranslation(ObjectNode query, ObjectNode namedQueries) {
    }
}
