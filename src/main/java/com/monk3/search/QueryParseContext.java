package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.DocumentMapping;
import com.monk3.mapping.FieldType;
import com.monk3.mapping.MappedField;
import com.monk3.mapping.SearchMapping;
import com.monk3.mapping.SearchMappingConfig;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record QueryParseContext(
        ObjectMapper objectMapper,
        SearchMapping mapping,
        DocumentMapping document,
        MappedField currentField,
        Integer minimumMatch,
        SearchMappingConfig config
) {
    public static QueryParseContext root(ObjectMapper objectMapper, SearchMapping mapping, SearchMappingConfig config) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                mapping.root(),
                null,
                null,
                config);
    }

    public ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    public JsonNode valueNode(Object value) {
        return objectMapper.valueToTree(value);
    }

    public QueryParseContext withMinimumMatch(Integer minimumMatch) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                document,
                currentField,
                minimumMatch,
                config);
    }

    public QueryParseContext withField(MappedField mappedField) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                document,
                mappedField,
                minimumMatch,
                config);
    }

    public QueryParseContext withDocument(DocumentMapping documentMapping) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                documentMapping,
                null,
                minimumMatch,
                config);
    }

    public int minimumMatchOrDefault(int defaultValue) {
        return minimumMatch == null ? defaultValue : minimumMatch;
    }

    public JsonNode boolShould(SearchEngine searchEngine, int minimumMatch, List<JsonNode> clauses) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        clauses.forEach(should::add);
        bool.put(searchEngine.minimumShouldMatchProperty(), minimumMatch);
        return root;
    }

    public JsonNode boolMust(List<JsonNode> clauses) {
        ObjectNode root = objectNode();
        ArrayNode must = root.putObject("bool").putArray("must");
        clauses.forEach(must::add);
        return root;
    }

    public JsonNode elasticsearchMatchPhrase(String field, String phrase) {
        ObjectNode root = objectNode();
        root.putObject("match_phrase").put(field, phrase);
        return root;
    }

    public MappedField requireMappedField(String logicalName) {
        return findMappedField(logicalName)
                .orElseThrow(() -> new QueryTranslationException(
                        "Field '" + logicalName + "' is not defined in mapping document '" + document.name()
                                + "' for material type '" + mapping.materialType() + "'"));
    }

    public Optional<MappedField> findMappedField(String logicalName) {
        return document.field(logicalName);
    }

    public DocumentMapping requireDocument(String documentName) {
        return mapping.document(documentName)
                .orElseThrow(() -> new QueryTranslationException(
                        "Document type '" + documentName + "' is not defined for material type '" + mapping.materialType() + "'"));
    }

    public String requireSearchField(String queryType, FieldType... supportedTypes) {
        if (currentField == null) {
            throw new QueryTranslationException("No mapped field is available for " + queryType + " query conversion");
        }
        boolean supported = List.of(supportedTypes).contains(currentField.type());
        if (!supported) {
            throw new QueryTranslationException(
                    "Query type '" + queryType + "' is not supported for field '" + currentField.logicalName()
                            + "' with mapping type '" + currentField.type().name().toLowerCase(Locale.ROOT) + "'");
        }
        return currentField.searchField();
    }

    public JsonNode elasticsearchMustNot(JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.add(objectNode().set("match_all", objectNode()));
        ArrayNode mustNot = bool.putArray("must_not");
        mustNot.add(query);
        return root;
    }

    public JsonNode solrMustNot(JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.add("*:*");
        ArrayNode mustNot = bool.putArray("must_not");
        mustNot.add(query);
        return root;
    }

    public JsonNode elasticsearchNestedQuery(String path, JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode nested = root.putObject("nested");
        nested.put("path", path);
        nested.set("query", query);
        return root;
    }

    public JsonNode solrParentQuery(JsonNode childQuery) {
        ObjectNode root = objectNode();
        ObjectNode parent = root.putObject("parent");
        parent.put("which", config.solr().parentBlockMask());
        parent.set("query", childQuery);
        return root;
    }

    public JsonNode elasticsearchMaterialTypeScope(String materialType, JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        ObjectNode materialTypeFilter = objectNode();
        materialTypeFilter.putObject("term").put(config.materialTypeField(), materialType);
        filter.add(materialTypeFilter);
        ArrayNode must = bool.putArray("must");
        must.add(query);
        return root;
    }

    public JsonNode solrMaterialTypeScope(String materialType, JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.add(solrFieldQuery(config.materialTypeField(), materialType));
        ArrayNode must = bool.putArray("must");
        must.add(query);
        return root;
    }

    public JsonNode solrFieldQuery(String field, String value) {
        ObjectNode root = objectNode();
        ObjectNode fieldQuery = root.putObject("field");
        fieldQuery.put("f", field);
        fieldQuery.put("query", value);
        return root;
    }
}
