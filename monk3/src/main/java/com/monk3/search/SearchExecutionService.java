package com.monk3.search;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.model.Aggregation;
import com.monk3.model.AggregationResult;
import com.monk3.model.BackendQuery;
import com.monk3.model.SearchExecutionRequest;
import com.monk3.model.SearchExecutionResponse;
import com.monk3.model.SearchResult;
import com.monk3.search.QueryTranslationService.BackendTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;

@ApplicationScoped
@RequiredArgsConstructor
public class SearchExecutionService {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final JsonPointer ELASTICSEARCH_MAX_SCORE_PATH = JsonPointer.compile("/hits/max_score");

    private final ObjectMapper objectMapper;
    private final QueryTranslationService queryTranslationService;
    private final ConfigurationCatalogService catalogService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

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
                        .sorted(Comparator.comparingDouble(SearchResult::normalizedScore)
                                .thenComparingDouble(SearchResult::score)
                                .reversed())
                        .limit(request.size() != null ? request.size() : Long.MAX_VALUE)
                        .toList(),
                hasAggregations(request) ? aggregationsByBackend(backendResults) : null);
    }

    private List<BackendSearchResult> executeBackendSearches(SearchExecutionRequest request) {
        List<Callable<BackendSearchResult>> searches = queryTranslationService.resolveTargets(request.query()).stream()
                .map(target -> (Callable<BackendSearchResult>) () -> searchBackend(target, request))
                .toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.invokeAll(searches).stream()
                    .map(SearchExecutionService::completed)
                    .toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchExecutionException("Search request was interrupted", exception);
        }
    }

    private static BackendSearchResult completed(Future<BackendSearchResult> search) {
        try {
            return search.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchExecutionException("Search request was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new SearchExecutionException("Search backend request failed", exception.getCause());
        }
    }

    /**
     * Translates the request into each backend's native body without executing it. Returns the exact
     * body {@link #search} would POST per backend (query, result options, and aggregations).
     */
    public List<BackendQuery> parse(SearchExecutionRequest request) {
        return queryTranslationService.resolveTargets(request.query()).stream()
                .map(target -> new BackendQuery(
                        target.name(), target.engine(), target.materialTypes(),
                        buildRequestBody(target, request, projections(target, request.fields()))))
                .toList();
    }

    private BackendSearchResult searchBackend(BackendTarget target, SearchExecutionRequest request) {
        List<FieldProjection> projections = projections(target, request.fields());
        ObjectNode body = buildRequestBody(target, request, projections);

        JsonNode response = postJson(target.name(), targetUri(target.backend(), target.engine()), body);
        return new BackendSearchResult(
                target.name(),
                parseResponse(target, projections, response),
                hasAggregations(request) ? parseAggregations(target, request.aggs(), response) : null);
    }

    private ObjectNode buildRequestBody(
            BackendTarget target,
            SearchExecutionRequest request,
            List<FieldProjection> projections
    ) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        QueryTranslationService.QueryTranslation queryTranslation = queryTranslationService.translate(target);
        body.set("query", queryTranslation.query());
        ObjectNode namedQueries = queryTranslation.namedQueries();
        applyResultOptions(target, body, projections, request);
        if (hasAggregations(request)) {
            QueryTranslationService.AggregationTranslation translation =
                    queryTranslationService.translateAggregations(target, request.aggs());
            body.set(target.engine().aggregationsRequestProperty(), translation.aggregations());
            namedQueries.setAll(translation.namedQueries());
        }
        if (!namedQueries.isEmpty()) {
            body.set("queries", namedQueries);
        }
        return body;
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
            SearchExecutionRequest request
    ) {
        Set<String> storedFields = storedFields(target.backend().primaryKey(), projections);
        body.put(target.engine().sizeProperty(), size(request, target.backend()));
        switch (target.engine()) {
            case ELASTICSEARCH -> storedFields.forEach(body.putArray("_source")::add);
            case SOLR -> {
                ArrayNode fields = body.putArray("fields");
                fields.add("score");
                storedFields.forEach(fields::add);
            }
        }
    }

    private JsonNode postJson(String backendName, URI uri, JsonNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SearchExecutionException(
                        "Search backend '" + backendName + "' returned HTTP " + response.statusCode() + " - " + new String(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new SearchExecutionException("Search backend '" + backendName + "' returned invalid JSON", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchExecutionException("Search backend '" + backendName + "' request was interrupted", exception);
        }
    }

    private List<SearchResult> parseResponse(
            BackendTarget target,
            List<FieldProjection> projections,
            JsonNode response
    ) {
        ArrayNode hits = arrayAt(response.at(target.engine().resultsPath()));
        double maxScore = maxScore(target.engine(), response, hits);
        return StreamSupport.stream(hits.spliterator(), false)
                .map(hit -> searchResult(target, projections, hit, maxScore))
                .toList();
    }

    private SearchResult searchResult(
            BackendTarget target,
            List<FieldProjection> projections,
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
                logicalFields(document, projections));
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

    private Set<String> storedFields(String primaryKey, List<FieldProjection> projections) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(primaryKey);
        projections.stream()
                .map(FieldProjection::storedField)
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

    private record BackendSearchResult(
            String backend,
            List<SearchResult> results,
            Map<String, AggregationResult> aggregations
    ) {
    }
}
