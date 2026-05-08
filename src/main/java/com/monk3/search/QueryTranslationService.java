package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.MappingRepository;
import com.monk3.mapping.SearchMapping;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.SearchQueryRequest;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.util.List;

@ApplicationScoped
@RequiredArgsConstructor
public class QueryTranslationService {
    private final ObjectMapper objectMapper;
    private final MappingRepository mappingRepository;
    private final SearchMappingConfig config;

    public ObjectNode translate(SearchEngine searchEngine, SearchQueryRequest request) {
        List<JsonNode> materialQueries = request.materialTypes().stream()
                .map(materialType -> translateMaterialType(searchEngine, request, materialType))
                .toList();

        ObjectNode response = objectMapper.createObjectNode();
        response.set("query", combineMaterialQueries(searchEngine, materialQueries));
        return response;
    }

    private JsonNode translateMaterialType(
            SearchEngine searchEngine,
            SearchQueryRequest request,
            String materialType
    ) {
        SearchMapping mapping = mappingRepository.mappingForMaterialType(materialType);
        QueryParseContext context = QueryParseContext.root(objectMapper, mapping, config);
        return switch (searchEngine) {
            case ELASTICSEARCH -> context.elasticsearchMaterialTypeScope(
                    materialType,
                    request.query().toElasticsearch(context));
            case SOLR -> context.solrMaterialTypeScope(
                    materialType,
                    request.query().toSolr(context));
        };
    }

    private JsonNode combineMaterialQueries(SearchEngine searchEngine, List<JsonNode> materialQueries) {
        if (materialQueries.size() == 1) {
            return materialQueries.get(0);
        }

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        materialQueries.forEach(should::add);
        bool.put(searchEngine.minimumShouldMatchProperty(), 1);
        return root;
    }
}
