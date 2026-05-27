package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.SearchExecutionRequest;
import com.monk3.model.SearchExecutionResponse;
import com.monk3.model.SearchQueryRequest;
import com.monk3.model.SearchResult;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
import java.util.stream.StreamSupport;

@ApplicationScoped
@RequiredArgsConstructor
public class SearchExecutionService {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final ObjectMapper objectMapper;
    private final QueryTranslationService queryTranslationService;
    private final ConfigurationCatalogService catalogService;
    private final SearchMappingConfig config;
    private final BackendsConfigLoader backendsConfigLoader;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public SearchExecutionResponse search(SearchExecutionRequest request) {
        List<SearchResult> results = executeBackendSearches(request);

        if (results.isEmpty()) {
            throw new QueryTranslationException("No configured search backend supports the requested material types");
        }

        return new SearchExecutionResponse(results.stream()
                .sorted(Comparator.comparingDouble(SearchResult::normalizedScore)
                        .thenComparingDouble(SearchResult::score)
                        .reversed())
                .limit(size(request, null))
                .toList());
    }

    private List<SearchResult> executeBackendSearches(SearchExecutionRequest request) {
        Map<String, List<String>> materialTypesByBackend = new LinkedHashMap<>();
        for (String materialType : request.query().materialTypes()) {
            String backendName = catalogService.backendForMaterialType(materialType);
            materialTypesByBackend.computeIfAbsent(backendName, k -> new ArrayList<>()).add(materialType);
        }

        List<Callable<List<SearchResult>>> searches = materialTypesByBackend.entrySet().stream()
                .map(entry -> resolveTarget(entry.getKey(), entry.getValue()))
                .map(target -> (Callable<List<SearchResult>>) () -> searchBackend(target, request))
                .toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.invokeAll(searches).stream()
                    .flatMap(search -> completed(search).stream())
                    .toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchExecutionException("Search request was interrupted", exception);
        }
    }

    private BackendTarget resolveTarget(String backendName, List<String> materialTypes) {
        SearchMappingConfig.Backend backend = backendsConfigLoader.backends().get(backendName);
        if (backend == null) {
            throw new QueryTranslationException(
                    "No configured search backend named '" + backendName + "'");
        }
        return new BackendTarget(backendName, backend, searchEngine(backend), materialTypes);
    }

    private static List<SearchResult> completed(java.util.concurrent.Future<List<SearchResult>> search) {
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

    private List<SearchResult> searchBackend(BackendTarget target, SearchExecutionRequest request) {
        SearchQueryRequest backendQuery = new SearchQueryRequest(
                request.query().id(),
                request.query().name(),
                target.materialTypes(),
                request.query().query());
        List<FieldProjection> projections = projections(target.materialTypes(), request.fields());
        ObjectNode body = queryTranslationService.translate(target.engine(), backendQuery);
        applyResultOptions(target, body, projections, request);

        JsonNode response = postJson(target.name(), targetUri(target.backend(), target.engine()), body);
        return parseResponse(target, projections, response);
    }

    private void applyResultOptions(BackendTarget target, ObjectNode body, List<FieldProjection> projections, SearchExecutionRequest request) {
        Set<String> storedFields = storedFields(target.materialTypes(), projections);
        body.put(target.engine() == SearchEngine.ELASTICSEARCH ? "size" : "limit", size(request, target.backend()));
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
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SearchExecutionException(
                        "Search backend '" + backendName + "' returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new SearchExecutionException("Search backend '" + backendName + "' returned invalid JSON", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchExecutionException("Search backend '" + backendName + "' request was interrupted", exception);
        }
    }

    private List<SearchResult> parseResponse(BackendTarget target, List<FieldProjection> projections, JsonNode response) {
        ArrayNode hits = arrayAt(response.at(target.engine().resultsPath()));
        double maxScore = maxScore(target.engine(), response, hits);
        return StreamSupport.stream(hits.spliterator(), false)
                .map(hit -> searchResult(target, projections, hit, maxScore))
                .toList();
    }

    private SearchResult searchResult(BackendTarget target, List<FieldProjection> projections, JsonNode hit, double maxScore) {
        JsonNode document = target.engine() == SearchEngine.ELASTICSEARCH ? hit.path("_source") : hit;
        double score = hit.path(target.engine().scoreField()).asDouble(0.0);
        return new SearchResult(
                target.name(),
                target.engine(),
                materialType(document, target.materialTypes()),
                id(document, target.engine() == SearchEngine.ELASTICSEARCH ? hit.path("_id").asText(null) : null, target.materialTypes()),
                score,
                normalizedScore(score, maxScore),
                logicalFields(document, projections));
    }

    private List<FieldProjection> projections(List<String> materialTypes, List<String> logicalFields) {
        return materialTypes.stream()
                .flatMap(materialType -> projections(materialType, logicalFields).stream())
                .toList();
    }

    private List<FieldProjection> projections(String materialType, List<String> logicalFields) {
        SearchMapping mapping = catalogService.mappingForMaterialType(materialType);
        return logicalFields.stream()
                .map(logicalField -> projection(mapping, materialType, logicalField))
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
        return new FieldProjection(logicalField, mappedField.searchField());
    }

    private Set<String> storedFields(List<String> materialTypes, List<FieldProjection> projections) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(config.materialTypeField());
        materialTypes.stream()
                .map(catalogService::mappingForMaterialType)
                .map(SearchMapping::primaryKey)
                .forEach(fields::add);
        projections.stream()
                .map(FieldProjection::storedField)
                .forEach(fields::add);
        return fields;
    }

    private Map<String, JsonNode> logicalFields(JsonNode document, List<FieldProjection> projections) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (FieldProjection projection : projections) {
            JsonNode value = document.get(projection.storedField());
            if (value != null && !value.isNull()) {
                fields.putIfAbsent(projection.logicalName(), value);
            }
        }
        return fields;
    }

    private String id(JsonNode document, String fallback, List<String> materialTypes) {
        return materialTypes.stream()
                .map(catalogService::mappingForMaterialType)
                .map(SearchMapping::primaryKey)
                .map(document::get)
                .filter(SearchExecutionService::present)
                .findFirst()
                .map(JsonNode::asText)
                .orElse(fallback);
    }

    private String materialType(JsonNode document, List<String> materialTypes) {
        JsonNode value = document.get(config.materialTypeField());
        if (value != null && !value.isNull()) {
            return value.asText();
        }
        return materialTypes.size() == 1 ? materialTypes.getFirst() : null;
    }

    private static ArrayNode arrayAt(JsonNode node) {
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return JsonNodeFactory.instance.arrayNode();
    }

    private static double observedMaxScore(ArrayNode docs) {
        double maxScore = 0.0;
        for (JsonNode doc : docs) {
            maxScore = Math.max(maxScore, doc.path("score").asDouble(doc.path("_score").asDouble(0.0)));
        }
        return maxScore;
    }

    private static double maxScore(SearchEngine engine, JsonNode response, ArrayNode hits) {
        return engine == SearchEngine.ELASTICSEARCH && response.at("/hits/max_score").isNumber()
                ? response.at("/hits/max_score").asDouble()
                : observedMaxScore(hits);
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

    private static URI targetUri(SearchMappingConfig.Backend backend, SearchEngine engine) {
        String base = trimTrailingSlash(backend.url().toString());
        return switch (engine) {
            case ELASTICSEARCH -> URI.create(base + "/" + requiredPathSegment(backend.index(), "index") + "/_search");
            case SOLR -> URI.create(base + "/" + requiredPathSegment(backend.collection(), "collection") + "/select");
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

    private static int size(SearchExecutionRequest request, SearchMappingConfig.Backend backend) {
        if (request.size() != null) {
            return request.size();
        }
        return backend == null ? Integer.MAX_VALUE : backend.defaultSize();
    }

    private static SearchEngine searchEngine(SearchMappingConfig.Backend backend) {
        return SearchEngine.valueOf(backend.engine().name());
    }

    private record BackendTarget(
            String name,
            SearchMappingConfig.Backend backend,
            SearchEngine engine,
            List<String> materialTypes
    ) {
    }

    private record FieldProjection(String logicalName, String storedField) {
    }
}
