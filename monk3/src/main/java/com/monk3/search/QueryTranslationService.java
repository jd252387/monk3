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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * Routes each query's material types to backends and groups by backend. Within a single query,
     * material types resolving to the same backend share that query (their per-material-type filters are
     * OR-combined at translation time). Across queries, every query that routes to a backend contributes
     * its own {@link BackendTarget.MaterialQuery}; those are merged with a boolean should in {@link #translate}.
     * Backend order follows first appearance across the queries.
     */
    public List<BackendTarget> resolveTargets(List<SearchQueryRequest> requests) {
        return requests.stream()
                .flatMap(this::resolveQueryTargets)
                .collect(Collectors.groupingBy(ResolvedQuery::backendName, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(group -> new BackendTarget(
                        group.getFirst().backendName(),
                        group.getFirst().backend(),
                        group.getFirst().engine(),
                        group.stream().map(ResolvedQuery::materialQuery).toList()))
                .toList();
    }

    /**
     * Resolves a single query to one {@link ResolvedQuery} per backend its material types target, grouping the
     * material types that share a backend so they translate against that backend's single context.
     */
    private Stream<ResolvedQuery> resolveQueryTargets(SearchQueryRequest request) {
        QueryAnalysis analysis = QueryAnalyzer.analyze(request.query());
        return request.materialTypes().stream()
                .map(materialType -> resolveMaterialType(materialType, analysis))
                .collect(Collectors.groupingBy(ResolvedMaterialType::backendName, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(group -> new ResolvedQuery(
                        group.getFirst().backendName(),
                        group.getFirst().backend(),
                        group.getFirst().engine(),
                        new BackendTarget.MaterialQuery(
                                group.stream().map(ResolvedMaterialType::materialType).toList(),
                                request.query())));
    }

    private ResolvedMaterialType resolveMaterialType(String materialType, QueryAnalysis analysis) {
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
        return new ResolvedMaterialType(materialType, backendName, backendConfig, SearchEngine.of(backendConfig.engine()));
    }

    public QueryTranslation translate(BackendTarget target) {
        SearchEngine engine = target.engine();
        QueryParseContext context = contextForBackend(target.name());
        if (engine == SearchEngine.SOLR) {
            context = context.withSolrRootIdentifier(translateRootIdentifier(context, engine));
        }
        QueryParseContext queryContext = context;
        List<JsonNode> perQueryBools = target.queries().stream()
                .map(materialQuery -> translateMaterialQuery(materialQuery, engine, queryContext))
                .toList();
        ObjectNode root = (ObjectNode) QueryJson.shouldOrSingle(engine, perQueryBools);
        return new QueryTranslation(root, context.solrNamedQueries());
    }

    /**
     * Translates one query that routed to this backend into its {@code bool}: the user query in {@code must},
     * plus its material types' optional catalog filters OR-combined in {@code filter}. Multiple such bools for
     * the same backend (one per originating query) are merged with a boolean should by {@link #translate}.
     */
    private JsonNode translateMaterialQuery(BackendTarget.MaterialQuery materialQuery, SearchEngine engine, QueryParseContext context) {
        JsonNode query = materialQuery.query().translate(engine, context);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        List<JsonNode> filters = translateMappingFilters(materialQuery.materialTypes(), engine, context);
        if (!filters.isEmpty()) {
            bool.putArray("filter").add(QueryJson.shouldOrSingle(engine, filters));
        }
        bool.putArray("must").add(query);
        return root;
    }

    /**
     * Translates each material type's optional {@code filter} (a DSL {@code QueryNode} declared in
     * {@code catalog.json}) into engine JSON. Material types declaring no filter contribute nothing; all
     * material types share one backend, so the single per-backend {@code context} applies to each.
     */
    private List<JsonNode> translateMappingFilters(List<String> materialTypes, SearchEngine engine, QueryParseContext context) {
        return materialTypes.stream()
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
            BackendConfig backend,
            SearchEngine engine,
            List<MaterialQuery> queries
    ) {
        /** Distinct union of the material types across every query that routed to this backend. */
        public List<String> materialTypes() {
            return queries.stream()
                    .flatMap(materialQuery -> materialQuery.materialTypes().stream())
                    .distinct()
                    .toList();
        }

        /** One originating query's material types (sharing this backend) paired with that query's node. */
        public record MaterialQuery(List<String> materialTypes, QueryNode query) {
        }
    }

    /** One backend a single query routed to, with the material types that share it grouped into a {@link BackendTarget.MaterialQuery}. */
    private record ResolvedQuery(String backendName, BackendConfig backend, SearchEngine engine, BackendTarget.MaterialQuery materialQuery) {
    }

    /** One material type resolved to its backend, before material types are grouped per backend. */
    private record ResolvedMaterialType(String materialType, String backendName, BackendConfig backend, SearchEngine engine) {
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
