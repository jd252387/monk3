package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.BackendQuery;
import com.monk3.model.SearchQueryRequest;
import com.monk3.routing.QueryAnalysis;
import com.monk3.routing.QueryAnalyzer;
import com.monk3.routing.RoutingEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
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
    private final BackendsConfigLoader backendsConfigLoader;
    private final RoutingEngine routingEngine;

    public List<BackendQuery> translateByBackend(SearchQueryRequest request) {
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
                .map(entry -> buildBackendQuery(entry.getKey(), entry.getValue(), request))
                .toList();
    }

    private BackendQuery buildBackendQuery(String backendName, List<String> materialTypes, SearchQueryRequest request) {
        SearchMappingConfig.Backend backendConfig = backendsConfigLoader.backends().get(backendName);
        if (backendConfig == null) {
            throw new QueryTranslationException("No configured search backend named '" + backendName + "'");
        }
        SearchEngine engine = SearchEngine.valueOf(backendConfig.engine().name());
        SearchQueryRequest groupedRequest = new SearchQueryRequest(
                request.id(), request.name(), materialTypes, request.query());
        ObjectNode translated = translate(engine, groupedRequest);
        return new BackendQuery(backendName, engine, materialTypes, (ObjectNode) translated.get("query"));
    }

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
        SearchMapping mapping = catalogService.mappingForMaterialType(materialType);
        VirtualMapping virtualMapping = catalogService.virtualMappingForMaterialType(materialType).orElse(null);
        QueryParseContext context = QueryParseContext.root(mapping, config, virtualMapping, virtualFieldExpander);
        JsonNode query = searchEngine == SearchEngine.ELASTICSEARCH
                ? request.query().toElasticsearch(context)
                : request.query().toSolr(context);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        JsonNode filter;
        if (searchEngine == SearchEngine.ELASTICSEARCH) {
            filter = JsonNodeFactory.instance.objectNode()
                    .set("term", JsonNodeFactory.instance.objectNode().put(config.materialTypeField(), materialType));
        } else {
            ObjectNode fieldQuery = JsonNodeFactory.instance.objectNode();
            fieldQuery.putObject("field")
                    .put("f", config.materialTypeField())
                    .set("query", QueryJson.valueNode(materialType));
            filter = fieldQuery;
        }
        bool.putArray("filter").add(filter);
        bool.putArray("must").add(query);
        return root;
    }

    private JsonNode combineMaterialQueries(SearchEngine searchEngine, List<JsonNode> materialQueries) {
        if (materialQueries.size() == 1) {
            return materialQueries.get(0);
        }

        return QueryJson.boolShould(searchEngine, 1, materialQueries);
    }
}
