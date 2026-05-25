package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public sealed interface ExactQuery<T> extends QueryPayload
        permits ExactQuery.Numeric, ExactQuery.Datetime, ExactQuery.BooleanValues {
    JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    default String type() {
        return "exact";
    }

    @NotEmpty
    List<@NotNull T> values();

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JSON.objectNode();
        ArrayNode fieldValues = root.putObject("terms").putArray(field);
        values().stream().map(QueryJson::valueNode).forEach(fieldValues::add);
        return root;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        if (values().size() == 1) {
            ObjectNode root = JSON.objectNode();
            ObjectNode fieldQuery = root.putObject("field");
            fieldQuery.put("f", field).set("query", QueryJson.valueNode(values().getFirst()));
            return root;
        }

        return QueryJson.boolShould(SearchEngine.SOLR, 1, values().stream()
                .map(value -> {
                    ObjectNode root = JSON.objectNode();
                    ObjectNode fieldQuery = root.putObject("field");
                    fieldQuery.put("f", field).set("query", QueryJson.valueNode(value));
                    return root;
                })
                .map(JsonNode.class::cast)
                .toList());
    }

    record Numeric(List<BigDecimal> values) implements ExactQuery<BigDecimal> {
    }

    record Datetime(List<String> values) implements ExactQuery<String> {
    }

    record BooleanValues(List<Boolean> values) implements ExactQuery<Boolean> {
    }
}
