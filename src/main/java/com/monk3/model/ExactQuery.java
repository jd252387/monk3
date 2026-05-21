package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.json.ExactQueryDeserializer;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

@JsonDeserialize(using = ExactQueryDeserializer.class)
public sealed interface ExactQuery<T> extends QueryPayload
        permits NumericExactQuery, DatetimeExactQuery, BooleanExactQuery {
    @NotEmpty
    List<@NotNull T> values();

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode fieldValues = root.putObject("terms").putArray(field);
        values().stream()
                .map(ExactQuery::valueNode)
                .forEach(fieldValues::add);
        return root;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        if (values().size() == 1) {
            return solrFieldQuery(field, values().getFirst());
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        values().stream()
                .map(value -> solrFieldQuery(field, value))
                .forEach(should::add);
        bool.put(SearchEngine.SOLR.minimumShouldMatchProperty(), 1);
        return root;
    }

    private static ObjectNode solrFieldQuery(String field, Object value) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode fieldQuery = root.putObject("field");
        fieldQuery.put("f", field);
        fieldQuery.set("query", valueNode(value));
        return root;
    }

    private static JsonNode valueNode(Object value) {
        if (value instanceof String string) {
            return JsonNodeFactory.instance.textNode(string);
        }
        if (value instanceof Boolean booleanValue) {
            return JsonNodeFactory.instance.booleanNode(booleanValue);
        }
        if (value instanceof Integer integer) {
            return JsonNodeFactory.instance.numberNode(integer);
        }
        if (value instanceof Long longValue) {
            return JsonNodeFactory.instance.numberNode(longValue);
        }
        if (value instanceof BigInteger bigInteger) {
            return JsonNodeFactory.instance.numberNode(bigInteger);
        }
        if (value instanceof BigDecimal bigDecimal) {
            return JsonNodeFactory.instance.numberNode(bigDecimal);
        }
        if (value instanceof Float floatValue) {
            return JsonNodeFactory.instance.numberNode(floatValue);
        }
        if (value instanceof Double doubleValue) {
            return JsonNodeFactory.instance.numberNode(doubleValue);
        }
        if (value instanceof Number number) {
            return JsonNodeFactory.instance.numberNode(number.doubleValue());
        }
        throw new IllegalArgumentException("Exact query value must be a string, number, or boolean");
    }
}
