package com.monk.search;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.agg.Aggregation;
import com.monk.model.agg.AggregationResult;
import com.monk.model.BackendQuery;
import com.monk.model.SearchExecutionRequest;
import com.monk.model.SearchExecutionResponse;
import com.monk.model.SearchQueryRequest;
import com.monk.model.SearchResult;
import com.monk.model.SortClause;
import com.monk.model.SortOrder;
import com.monk.model.query.BooleanQueryData;
import com.monk.model.query.QueryNode;
import com.monk.search.QueryTranslationService.BackendTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import lombok.RequiredArgsConstructor;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
@RequiredArgsConstructor
public class SearchExecutionService {
    private static final Logger LOG = Logger.getLogger(SearchExecutionService.class);
    private static final JsonPointer ELASTICSEARCH_MAX_SCORE_PATH = JsonPointer.compile("/hits/max_score");

    private final QueryTranslationService queryTranslationService;
    private final ConfigurationCatalogService catalogService;
    private final BackendHttpClient backendHttpClient;

    public SearchExecutionResponse search(SearchExecutionRequest request) {
        List<BackendSearchResult> backendResults = executeBackendSearches(request);
        if (backendResults.isEmpty()) {
            throw new QueryTranslationException("No configured search backend supports the requested material types");
        }
        List<SearchResult> results = backendResults.stream()
                .flatMap(backendResult -> backendResult.results().stream())
                .toList();

        return new SearchExecutionResponse(
                results.stream()
                        .sorted(mergeOrdering(request.sort()))
                        .limit(request.size() != null ? request.size() : Long.MAX_VALUE)
                        .toList(),
                hasAggregations(request) ? aggregationsByBackend(backendResults) : null,
                mergedMatchedQueries(backendResults));
    }

    /** Default relevance ordering (score descending) unless the request asked for explicit sort keys. */
    private static Comparator<SearchResult> mergeOrdering(List<SortClause> sort) {
        if (sort == null || sort.isEmpty()) {
            return Comparator.comparingDouble(SearchResult::normalizedScore)
                    .thenComparingDouble(SearchResult::score)
                    .reversed();
        }
        Comparator<SearchResult> ordering = clauseComparator(sort.getFirst());
        for (SortClause clause : sort.subList(1, sort.size())) {
            ordering = ordering.thenComparing(clauseComparator(clause));
        }
        return ordering;
    }

    private static Comparator<SearchResult> clauseComparator(SortClause clause) {
        if (clause.isScore()) {
            Comparator<SearchResult> byScore = Comparator.comparingDouble(SearchResult::normalizedScore)
                    .thenComparingDouble(SearchResult::score);
            return clause.order() == SortOrder.DESC ? byScore.reversed() : byScore;
        }
        // ponytail: numeric-vs-lexical only (ISO-8601 datetimes sort correctly as text); missing sorts
        // last in both directions. Upgrade to locale collation / typed compare if a field needs it.
        Comparator<JsonNode> values = SearchExecutionService::compareValues;
        Comparator<JsonNode> directed = Comparator.nullsLast(
                clause.order() == SortOrder.DESC ? values.reversed() : values);
        return Comparator.comparing(result -> sortFieldValue(result, clause.field()), directed);
    }

    /** A result's sort value for a logical field, or {@code null} when the field is absent/null. */
    private static JsonNode sortFieldValue(SearchResult result, String field) {
        JsonNode value = result.sortValues() == null ? null : result.sortValues().get(field);
        return present(value) ? value : null;
    }

    private static int compareValues(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return Double.compare(left.asDouble(), right.asDouble());
        }
        return left.asText().compareTo(right.asText());
    }

    /** Merges each backend's matched-query map by name; null when no query was named. */
    private static Map<String, List<String>> mergedMatchedQueries(List<BackendSearchResult> backendResults) {
        Map<String, List<String>> matchedQueries = new LinkedHashMap<>();
        for (BackendSearchResult backendResult : backendResults) {
            backendResult.matchedQueries().forEach((name, ids) ->
                    matchedQueries.computeIfAbsent(name, key -> new ArrayList<>()).addAll(ids));
        }
        return matchedQueries.isEmpty() ? null : matchedQueries;
    }

    private List<BackendSearchResult> executeBackendSearches(SearchExecutionRequest request) {
        List<BackendTarget> targets = queryTranslationService.resolveTargets(request.query());
        List<Callable<BackendSearchResult>> searches = targets.stream()
                .map(target -> (Callable<BackendSearchResult>) () -> searchBackend(target, request))
                .toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<BackendSearchResult>> futures = executor.invokeAll(searches);
            List<BackendSearchResult> results = new ArrayList<>();
            int failures = 0;
            for (int index = 0; index < futures.size(); index++) {
                BackendTarget target = targets.get(index);
                try {
                    results.add(futures.get(index).get());
                } catch (ExecutionException exception) {
                    // A backend HTTP failure drops just that backend so the fan-out still returns
                    // partial results; translation/embedding errors (any other cause) fail the request.
                    if (!(exception.getCause() instanceof SearchExecutionException backendFailure)) {
                        throw rethrow(exception.getCause());
                    }
                    failures++;
                    LOG.warnf("Search backend '%s' failed; returning partial results without it: %s",
                            target.name(), backendFailure.getMessage());
                    results.add(BackendSearchResult.empty(target.name()));
                }
            }
            if (!targets.isEmpty() && failures == targets.size()) {
                throw new SearchExecutionException("All search backends failed");
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchExecutionException("Search request was interrupted", exception);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new SearchExecutionException("Search backend request failed", cause);
    }

    /**
     * Translates the request into each backend's native body without executing it. Returns the exact
     * body {@link #search} would POST per backend (query, result options, and aggregations).
     */
    public List<BackendQuery> parse(SearchExecutionRequest request) {
        return queryTranslationService.resolveTargets(request.query()).stream()
                .map(target -> new BackendQuery(
                        target.name(), target.engine(), target.materialTypes(),
                        buildRequestBody(target, request, projections(target, request.fields()),
                                sortResolutions(target, request.sort()))))
                .toList();
    }

    private BackendSearchResult searchBackend(BackendTarget target, SearchExecutionRequest request) {
        List<FieldProjection> projections = projections(target, request.fields());
        List<SortResolution> sortResolutions = sortResolutions(target, request.sort());
        ObjectNode body = buildRequestBody(target, request, projections, sortResolutions);

        JsonNode response = backendHttpClient.post(
                target.name(), target.engine().name(), targetUri(target.backend(), target.engine()), body);
        return new BackendSearchResult(
                target.name(),
                parseResponse(target, projections, sortResolutions, response),
                matchedQueries(target, response),
                hasAggregations(request) ? parseAggregations(target, request.aggs(), response) : null);
    }

    /**
     * Extracts which documents matched each named query. Solr's MatchedQueriesComponent reports a ready
     * name-to-ids summary; Elasticsearch reports a per-hit {@code matched_queries} list, inverted here
     * using the same document-id resolution as the results. Empty when the query named nothing.
     */
    private static Map<String, List<String>> matchedQueries(BackendTarget target, JsonNode response) {
        Map<String, List<String>> matched = new LinkedHashMap<>();
        switch (target.engine()) {
            case SOLR -> response.path("matched_queries_summary").fields().forEachRemaining(entry ->
                    entry.getValue().forEach(id ->
                            matched.computeIfAbsent(entry.getKey(), name -> new ArrayList<>()).add(id.asText())));
            case ELASTICSEARCH -> {
                for (JsonNode hit : arrayAt(response.at(target.engine().resultsPath()))) {
                    String id = id(hit.path("_source"), hit.path("_id").asText(null), target.backend().primaryKey());
                    hit.path("matched_queries").forEach(name ->
                            matched.computeIfAbsent(name.asText(), key -> new ArrayList<>()).add(id));
                }
            }
        }
        return matched;
    }

    private ObjectNode buildRequestBody(
            BackendTarget target,
            SearchExecutionRequest request,
            List<FieldProjection> projections,
            List<SortResolution> sortResolutions
    ) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        QueryTranslationService.QueryTranslation queryTranslation = queryTranslationService.translate(target);
        body.set("query", queryTranslation.query());
        ObjectNode namedQueries = queryTranslation.namedQueries();
        applyResultOptions(target, body, projections, sortResolutions, request);
        if (hasAggregations(request)) {
            QueryTranslationService.AggregationTranslation translation =
                    queryTranslationService.translateAggregations(target, request.aggs());
            body.set(target.engine().aggregationsRequestProperty(), translation.aggregations());
            namedQueries.setAll(translation.namedQueries());
        }
        if (!namedQueries.isEmpty()) {
            body.set("queries", namedQueries);
        }
        if (target.engine() == SearchEngine.SOLR) {
            ObjectNode params = body.putObject("params");
            params.put("uc", request.username());
            if (hasNamedQueries(request)) {
                params.put("matched_queries", true);
            }
        }
        return body;
    }

    // ponytail: detected per request, not per backend — a Solr backend whose slice names nothing
    // still gets matched_queries=true, which just yields empty sections.
    private static boolean hasNamedQueries(SearchExecutionRequest request) {
        return request.query().stream()
                .map(SearchQueryRequest::query)
                .anyMatch(SearchExecutionService::hasNamedQueries);
    }

    private static boolean hasNamedQueries(QueryNode node) {
        return node.name() != null
                || node.data() instanceof BooleanQueryData bool
                && bool.clauses().stream().anyMatch(SearchExecutionService::hasNamedQueries);
    }

    private static boolean hasAggregations(SearchExecutionRequest request) {
        return request.aggs() != null && !request.aggs().isEmpty();
    }

    private static Map<String, AggregationResult> parseAggregations(
            BackendTarget target,
            Map<String, Aggregation> aggs,
            JsonNode response
    ) {
        JsonNode aggregations = response.path(target.engine().aggregationsResponseProperty());
        Map<String, AggregationResult> results = new LinkedHashMap<>();
        aggs.forEach((name, aggregation) ->
                results.put(name, aggregation.parse(target.engine(), aggregations.path(name))));
        return results;
    }

    private static Map<String, Map<String, AggregationResult>> aggregationsByBackend(List<BackendSearchResult> backendResults) {
        Map<String, Map<String, AggregationResult>> aggregations = new LinkedHashMap<>();
        for (BackendSearchResult backendResult : backendResults) {
            if (backendResult.aggregations() != null) {
                aggregations.put(backendResult.backend(), backendResult.aggregations());
            }
        }
        return aggregations;
    }

    private void applyResultOptions(
            BackendTarget target,
            ObjectNode body,
            List<FieldProjection> projections,
            List<SortResolution> sortResolutions,
            SearchExecutionRequest request
    ) {
        Set<String> storedFields = storedFields(target.backend().primaryKey(), projections, sortResolutions);
        body.put(target.engine().sizeProperty(), size(request, target.backend()));
        switch (target.engine()) {
            case ELASTICSEARCH -> storedFields.forEach(body.putArray("_source")::add);
            case SOLR -> {
                ArrayNode fields = body.putArray("fields");
                fields.add("score");
                storedFields.forEach(fields::add);
            }
        }
        applySort(target, body, sortResolutions);
    }

    /**
     * Translates the resolved sort keys into the backend's native form: an Elasticsearch {@code sort} array of
     * {@code {field: {order}}} objects, or a Solr {@code sort} string ({@code "field asc, …"}). The relevance
     * token uses the engine's score field ({@code _score} / {@code score}).
     */
    private static void applySort(BackendTarget target, ObjectNode body, List<SortResolution> sortResolutions) {
        if (sortResolutions.isEmpty()) {
            return;
        }
        switch (target.engine()) {
            case ELASTICSEARCH -> {
                ArrayNode sort = body.putArray(target.engine().sortProperty());
                for (SortResolution resolution : sortResolutions) {
                    sort.addObject().putObject(sortField(target.engine(), resolution))
                            .put("order", resolution.clause().order().json());
                }
            }
            case SOLR -> body.put(target.engine().sortProperty(), sortResolutions.stream()
                    .map(resolution -> sortField(target.engine(), resolution) + " " + resolution.clause().order().json())
                    .collect(Collectors.joining(", ")));
        }
    }

    private static String sortField(SearchEngine engine, SortResolution resolution) {
        return resolution.storedField() != null ? resolution.storedField() : engine.scoreField();
    }

    /**
     * Resolves each requested sort key against the backend's mapping, enforcing {@code sortable}. The relevance
     * token ({@code _score}) resolves to a null stored field (handled natively); other keys resolve to the
     * mapped stored field, rejecting subdocument fields and fields not declared {@code sortable}.
     */
    private List<SortResolution> sortResolutions(BackendTarget target, List<SortClause> sort) {
        if (sort == null || sort.isEmpty()) {
            return List.of();
        }
        SearchMapping mapping = catalogService.mappingForBackend(target.name());
        String materialType = target.materialTypes().getFirst();
        return sort.stream()
                .map(clause -> new SortResolution(clause,
                        clause.isScore() ? null : sortField(mapping, materialType, clause.field())))
                .toList();
    }

    private static String sortField(SearchMapping mapping, String materialType, String logicalField) {
        MappedField mappedField = mapping.root()
                .field(logicalField)
                .orElseThrow(() -> new QueryTranslationException(
                        "Field '" + logicalField + "' is not defined for material type '" + materialType + "'"));
        if (mappedField.isSubdocument()) {
            throw new QueryTranslationException(
                    "Subdocument field '" + logicalField + "' cannot be used to sort results");
        }
        if (!mappedField.isSortable()) {
            throw new QueryTranslationException(
                    "Field '" + logicalField + "' is not sortable for material type '" + materialType + "'");
        }
        return mappedField.searchField();
    }

    private List<SearchResult> parseResponse(
            BackendTarget target,
            List<FieldProjection> projections,
            List<SortResolution> sortResolutions,
            JsonNode response
    ) {
        ArrayNode hits = arrayAt(response.at(target.engine().resultsPath()));
        double maxScore = maxScore(target.engine(), response, hits);
        return StreamSupport.stream(hits.spliterator(), false)
                .map(hit -> searchResult(target, projections, sortResolutions, hit, maxScore))
                .toList();
    }

    private SearchResult searchResult(
            BackendTarget target,
            List<FieldProjection> projections,
            List<SortResolution> sortResolutions,
            JsonNode hit,
            double maxScore
    ) {
        JsonNode document = target.engine() == SearchEngine.ELASTICSEARCH ? hit.path("_source") : hit;
        double score = hit.path(target.engine().scoreField()).asDouble(0.0);
        return new SearchResult(
                target.name(),
                target.engine(),
                id(document, target.engine() == SearchEngine.ELASTICSEARCH ? hit.path("_id").asText(null) : null, target.backend().primaryKey()),
                score,
                normalizedScore(score, maxScore),
                logicalFields(document, projections),
                sortValues(document, sortResolutions));
    }

    /** Sort-field values keyed by logical field name, so the cross-backend merge can order by them. */
    private static Map<String, JsonNode> sortValues(JsonNode document, List<SortResolution> sortResolutions) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (SortResolution resolution : sortResolutions) {
            if (resolution.storedField() != null) {
                values.put(resolution.clause().field(), document.get(resolution.storedField()));
            }
        }
        return values;
    }

    private List<FieldProjection> projections(BackendTarget target, List<String> logicalFields) {
        SearchMapping mapping = catalogService.mappingForBackend(target.name());
        return logicalFields.stream()
                .map(logicalField -> projection(mapping, target.materialTypes().getFirst(), logicalField))
                .toList();
    }

    private static FieldProjection projection(SearchMapping mapping, String materialType, String logicalField) {
        MappedField mappedField = mapping.root()
                .field(logicalField)
                .orElseThrow(() -> new QueryTranslationException(
                        "Field '" + logicalField + "' is not defined for material type '" + materialType + "'"));
        if (mappedField.isSubdocument()) {
            throw new QueryTranslationException(
                    "Subdocument field '" + logicalField + "' cannot be returned as a root result field");
        }
        if (!mappedField.isFetchable()) {
            throw new QueryTranslationException(
                    "Field '" + logicalField + "' is not fetchable for material type '" + materialType + "'");
        }
        return new FieldProjection(logicalField, mappedField.searchField());
    }

    private Set<String> storedFields(String primaryKey, List<FieldProjection> projections, List<SortResolution> sortResolutions) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(primaryKey);
        projections.stream()
                .map(FieldProjection::storedField)
                .forEach(fields::add);
        // A sort field must be fetched even when not projected, so the merge comparator can read its value.
        sortResolutions.stream()
                .map(SortResolution::storedField)
                .filter(Objects::nonNull)
                .forEach(fields::add);
        return fields;
    }

    private Map<String, JsonNode> logicalFields(JsonNode document, List<FieldProjection> projections) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (FieldProjection projection : projections) {
            JsonNode value = document.get(projection.storedField());
            if (present(value)) {
                fields.putIfAbsent(projection.logicalName(), value);
            }
        }
        return fields;
    }

    private static String id(JsonNode document, String fallback, String primaryKey) {
        JsonNode value = document.get(primaryKey);
        return present(value) ? value.asText() : fallback;
    }

    private static ArrayNode arrayAt(JsonNode node) {
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return JsonNodeFactory.instance.arrayNode();
    }

    private static double maxScore(SearchEngine engine, JsonNode response, ArrayNode hits) {
        if (engine == SearchEngine.ELASTICSEARCH) {
            JsonNode reported = response.at(ELASTICSEARCH_MAX_SCORE_PATH);
            if (reported.isNumber()) {
                return reported.asDouble();
            }
        }
        return observedMaxScore(engine.scoreField(), hits);
    }

    private static double observedMaxScore(String scoreField, ArrayNode docs) {
        double maxScore = 0.0;
        for (JsonNode doc : docs) {
            maxScore = Math.max(maxScore, doc.path(scoreField).asDouble(0.0));
        }
        return maxScore;
    }

    private static boolean present(JsonNode value) {
        return value != null && !value.isNull();
    }

    private static double normalizedScore(double score, double maxScore) {
        if (maxScore <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score / maxScore));
    }

    private static URI targetUri(BackendConfig backend, SearchEngine engine) {
        String base = trimTrailingSlash(backend.url().toString());
        return switch (engine) {
            case ELASTICSEARCH -> URI.create(base + "/" + requiredPathSegment(Optional.ofNullable(backend.index()), "index") + "/_search");
            case SOLR -> URI.create(base + "/" + requiredPathSegment(Optional.ofNullable(backend.collection()), "collection") + "/select");
        };
    }

    private static String requiredPathSegment(Optional<String> value, String name) {
        return value.filter(segment -> !segment.isBlank())
                .orElseThrow(() -> new QueryTranslationException("Search backend must configure " + name));
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static int size(SearchExecutionRequest request, BackendConfig backend) {
        return request.size() != null ? request.size() : backend.defaultSize();
    }

    private record FieldProjection(String logicalName, String storedField) {
    }

    /** A requested sort key resolved against a backend: its stored field, or {@code null} for the {@code _score} token. */
    private record SortResolution(SortClause clause, String storedField) {
    }

    private record BackendSearchResult(
            String backend,
            List<SearchResult> results,
            Map<String, List<String>> matchedQueries,
            Map<String, AggregationResult> aggregations
    ) {
        static BackendSearchResult empty(String backend) {
            return new BackendSearchResult(backend, List.of(), Map.of(), null);
        }
    }
}
