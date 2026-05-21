package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.json.RangeQueryDeserializer;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.AssertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;

@JsonDeserialize(using = RangeQueryDeserializer.class)
public sealed interface RangeQuery<T> extends QueryPayload permits NumericRangeQuery, DatetimeRangeQuery {
    T gte();

    T gt();

    T lte();

    T lt();

    @AssertTrue(message = "one lower range bound is required")
    default boolean hasLowerBound() {
        return gte() != null || gt() != null;
    }

    @AssertTrue(message = "one upper range bound is required")
    default boolean hasUpperBound() {
        return lte() != null || lt() != null;
    }

    @AssertTrue(message = "gte and gt cannot both be provided")
    default boolean hasSingleLowerBound() {
        return gte() == null || gt() == null;
    }

    @AssertTrue(message = "lte and lt cannot both be provided")
    default boolean hasSingleUpperBound() {
        return lte() == null || lt() == null;
    }

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("range", FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode range = root.putObject("range");
        ObjectNode fieldRange = range.putObject(field);
        putElasticsearchBound(fieldRange, "gte", gte());
        putElasticsearchBound(fieldRange, "gt", gt());
        putElasticsearchBound(fieldRange, "lte", lte());
        putElasticsearchBound(fieldRange, "lt", lt());
        return root;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("range", FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode range = root.putObject("frange");
        range.put("query", field);
        if (gte() != null) {
            range.set("l", valueNode(gte()));
            range.put("incl", true);
        }
        if (gt() != null) {
            range.set("l", valueNode(gt()));
            range.put("incl", false);
        }
        if (lte() != null) {
            range.set("u", valueNode(lte()));
            range.put("incu", true);
        }
        if (lt() != null) {
            range.set("u", valueNode(lt()));
            range.put("incu", false);
        }
        return root;
    }

    private static void putElasticsearchBound(
            ObjectNode fieldRange,
            String boundName,
            Object bound
    ) {
        if (bound != null) {
            fieldRange.set(boundName, valueNode(bound));
        }
    }

    private static JsonNode valueNode(Object value) {
        if (value instanceof String string) {
            return JsonNodeFactory.instance.textNode(string);
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
        throw new IllegalArgumentException("Range bound must be a string or number");
    }
}
