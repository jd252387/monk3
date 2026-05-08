package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.MappedField;
import com.monk3.mapping.MappingRepository;
import com.monk3.mapping.SearchMapping;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.SearchExecutionRequest;
import com.monk3.model.SearchExecutionResponse;
import com.monk3.model.SearchQueryRequest;
import com.monk3.model.SearchResult;
import jakarta.enterprise.context.ApplicationScoped;
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

@ApplicationScoped
@RequiredArgsConstructor
public class SearchExecutionService {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final ObjectMapper objectMapper;
    private final QueryTranslationService queryTranslationService;
    private final MappingRepository mappingRepository;
    private final SearchMappingConfig config;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public SearchExecutionResponse search(SearchExecutionRequest request) {
        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, SearchMappingConfig.Backend> entry : config.backends().entrySet()) {
            List<String> materialTypes = matchingMaterialTypes(entry.getValue(), request.query().materialTypes());
            if (!materialTypes.isEmpty()) {
                SearchEngine engine = searchEngine(entry.getValue());
                results.addAll(searchBackend(entry.getKey(), entry.getValue(), engine, request, materialTypes));
            }
        }

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

    private List<SearchResult> searchBackend(
            String backendName,
            SearchMappingConfig.Backend backend,
            SearchEngine engine,
            SearchExecutionRequest request,
            List<String> materialTypes) {
        SearchQueryRequest backendQuery = new SearchQueryRequest(
                request.query().id(),
                request.query().name(),
                materialTypes,
                request.query().query());
        List<FieldProjection> projections = projections(materialTypes, request.fields());
        ObjectNode body = queryTranslationService.translate(engine, backendQuery);
        applyResultOptions(engine, body, projections, materialTypes, request, backend);

        JsonNode response = postJson(backendName, targetUri(backend, engine), body);
        return switch (engine) {
            case ELASTICSEARCH -> parseElasticsearchResponse(backendName, engine, materialTypes, projections, response);
            case SOLR -> parseSolrResponse(backendName, engine, materialTypes, projections, response);
        };
    }

    private void applyResultOptions(
            SearchEngine engine,
            ObjectNode body,
            List<FieldProjection> projections,
            List<String> materialTypes,
            SearchExecutionRequest request,
            SearchMappingConfig.Backend backend) {
        int size = size(request, backend);
        switch (engine) {
            case ELASTICSEARCH -> {
                body.put("size", size);
                ArrayNode source = body.putArray("_source");
                storedFields(materialTypes, projections).forEach(source::add);
            }
            case SOLR -> {
                body.put("limit", size);
                ArrayNode fields = body.putArray("fields");
                fields.add("score");
                storedFields(materialTypes, projections).forEach(fields::add);
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

    private List<SearchResult> parseElasticsearchResponse(
            String backendName,
            SearchEngine engine,
            List<String> materialTypes,
            List<FieldProjection> projections,
            JsonNode response) {
        ArrayNode hits = arrayAt(response.at("/hits/hits"));
        double maxScore = Optional.ofNullable(response.at("/hits/max_score"))
                .filter(JsonNode::isNumber)
                .map(JsonNode::asDouble)
                .orElseGet(() -> observedMaxScore(hits));
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            double score = hit.path("_score").asDouble(0.0);
            results.add(new SearchResult(
                    backendName,
                    engine,
                    materialType(source, materialTypes),
                    id(source, hit.path("_id").asText(null), materialTypes),
                    score,
                    normalizedScore(score, maxScore),
                    logicalFields(source, projections)));
        }
        return results;
    }

    private List<SearchResult> parseSolrResponse(
            String backendName,
            SearchEngine engine,
            List<String> materialTypes,
            List<FieldProjection> projections,
            JsonNode response) {
        ArrayNode docs = arrayAt(response.at("/response/docs"));
        double maxScore = observedMaxScore(docs);
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode doc : docs) {
            double score = doc.path("score").asDouble(0.0);
            results.add(new SearchResult(
                    backendName,
                    engine,
                    materialType(doc, materialTypes),
                    id(doc, null, materialTypes),
                    score,
                    normalizedScore(score, maxScore),
                    logicalFields(doc, projections)));
        }
        return results;
    }

    private List<FieldProjection> projections(List<String> materialTypes, List<String> logicalFields) {
        List<FieldProjection> projections = new ArrayList<>();
        for (String materialType : materialTypes) {
            SearchMapping mapping = mappingRepository.mappingForMaterialType(materialType);
            for (String logicalField : logicalFields) {
                MappedField mappedField = mapping.root()
                        .field(logicalField)
                        .orElseThrow(() -> new QueryTranslationException(
                                "Field '" + logicalField + "' is not defined for material type '" + materialType + "'"));
                if (mappedField.isSubdocument()) {
                    throw new QueryTranslationException(
                            "Subdocument field '" + logicalField + "' cannot be returned as a root result field");
                }
                projections.add(new FieldProjection(logicalField, mappedField.searchField()));
            }
        }
        return projections;
    }

    private Set<String> storedFields(List<String> materialTypes, List<FieldProjection> projections) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(config.materialTypeField());
        materialTypes.stream()
                .map(mappingRepository::mappingForMaterialType)
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
        for (String materialType : materialTypes) {
            String primaryKey = mappingRepository.mappingForMaterialType(materialType).primaryKey();
            JsonNode value = document.get(primaryKey);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return fallback;
    }

    private String materialType(JsonNode document, List<String> materialTypes) {
        JsonNode value = document.get(config.materialTypeField());
        if (value != null && !value.isNull()) {
            return value.asText();
        }
        return materialTypes.size() == 1 ? materialTypes.get(0) : null;
    }

    private static ArrayNode arrayAt(JsonNode node) {
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return JsonNodeFactoryHolder.EMPTY_ARRAY;
    }

    private static double observedMaxScore(ArrayNode docs) {
        double maxScore = 0.0;
        for (JsonNode doc : docs) {
            maxScore = Math.max(maxScore, doc.path("score").asDouble(doc.path("_score").asDouble(0.0)));
        }
        return maxScore;
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
        return switch (backend.engine()) {
            case ELASTICSEARCH -> SearchEngine.ELASTICSEARCH;
            case SOLR -> SearchEngine.SOLR;
        };
    }

    private static List<String> matchingMaterialTypes(SearchMappingConfig.Backend backend, List<String> requestedMaterialTypes) {
        return requestedMaterialTypes.stream()
                .filter(backend.materialTypes()::contains)
                .toList();
    }

    private record FieldProjection(String logicalName, String storedField) {
    }

    private static final class JsonNodeFactoryHolder {
        private static final ArrayNode EMPTY_ARRAY = new ObjectMapper().createArrayNode();
    }
}
