package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    private final MappingRepository mappingRepository;
    private final SearchMappingConfig config;

    public ObjectNode translate(SearchEngine searchEngine, SearchQueryRequest request) {
        List<JsonNode> materialQueries = request.materialTypes().stream()
                .map(materialType -> translateMaterialType(searchEngine, request, materialType))
                .toList();

        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.set("query", combineMaterialQueries(searchEngine, materialQueries));
        return response;
    }

    private JsonNode translateMaterialType(
            SearchEngine searchEngine,
            SearchQueryRequest request,
            String materialType
    ) {
        SearchMapping mapping = mappingRepository.mappingForMaterialType(materialType);
        QueryParseContext context = QueryParseContext.root(mapping, config);
        JsonNode query = searchEngine == SearchEngine.ELASTICSEARCH
                ? request.query().toElasticsearch(context)
                : request.query().toSolr(context);
        return QueryJson.scopedMaterialType(searchEngine, config.materialTypeField(), materialType, query);
    }

    private JsonNode combineMaterialQueries(SearchEngine searchEngine, List<JsonNode> materialQueries) {
        if (materialQueries.size() == 1) {
            return materialQueries.get(0);
        }

        return QueryJson.boolShould(searchEngine, 1, materialQueries);
    }
}
