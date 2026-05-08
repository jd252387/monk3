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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class QueryParseContext {
    private final ObjectMapper objectMapper;
    private final SearchMapping mapping;
    private final DocumentMapping document;
    private final MappedField currentField;
    private final Integer minimumMatch;
    private final String materialTypeField;
    private final String solrParentBlockMask;

    public static QueryParseContext root(ObjectMapper objectMapper, SearchMapping mapping, SearchMappingConfig config) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                mapping.root(),
                null,
                null,
                config.materialTypeField(),
                config.solr().parentBlockMask());
    }

    public ObjectNode objectNode() {
        return objectMapper.createObjectNode();
    }

    public ArrayNode arrayNode() {
        return objectMapper.createArrayNode();
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
                materialTypeField,
                solrParentBlockMask);
    }

    public QueryParseContext withField(MappedField mappedField) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                document,
                mappedField,
                minimumMatch,
                materialTypeField,
                solrParentBlockMask);
    }

    public QueryParseContext withDocument(DocumentMapping documentMapping) {
        return new QueryParseContext(
                objectMapper,
                mapping,
                documentMapping,
                null,
                minimumMatch,
                materialTypeField,
                solrParentBlockMask);
    }

    public int minimumMatchOrDefault(int defaultValue) {
        return minimumMatch == null ? defaultValue : minimumMatch;
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
        boolean supported = Arrays.asList(supportedTypes).contains(currentField.type());
        if (!supported) {
            throw new QueryTranslationException(
                    "Query type '" + queryType + "' is not supported for field '" + currentField.logicalName()
                            + "' with mapping type '" + currentField.type().name().toLowerCase() + "'");
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
        parent.put("which", solrParentBlockMask);
        parent.set("query", childQuery);
        return root;
    }

    public JsonNode elasticsearchMaterialTypeScope(String materialType, JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        ObjectNode materialTypeFilter = objectNode();
        materialTypeFilter.putObject("term").put(materialTypeField, materialType);
        filter.add(materialTypeFilter);
        ArrayNode must = bool.putArray("must");
        must.add(query);
        return root;
    }

    public JsonNode solrMaterialTypeScope(String materialType, JsonNode query) {
        ObjectNode root = objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.add(solrFieldQuery(materialTypeField, materialType));
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
