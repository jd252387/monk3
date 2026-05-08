package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.math.BigInteger;

public record RangeQuery(
        @NotBlank String type,
        RangeBound gte,
        RangeBound gt,
        RangeBound lte,
        RangeBound lt
) implements QueryPayload {
    @JsonIgnore
    @AssertTrue(message = "type must be range")
    public boolean isRangeType() {
        return "range".equals(type);
    }

    @AssertTrue(message = "at least one range bound is required")
    public boolean hasAtLeastOneBound() {
        return gte != null || gt != null || lte != null || lt != null;
    }

    @AssertTrue(message = "gte and gt cannot both be provided")
    public boolean hasSingleLowerBound() {
        return gte == null || gt == null;
    }

    @AssertTrue(message = "lte and lt cannot both be provided")
    public boolean hasSingleUpperBound() {
        return lte == null || lt == null;
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("range", FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode range = root.putObject("range");
        ObjectNode fieldRange = range.putObject(field);
        putElasticsearchBound(fieldRange, "gte", gte);
        putElasticsearchBound(fieldRange, "gt", gt);
        putElasticsearchBound(fieldRange, "lte", lte);
        putElasticsearchBound(fieldRange, "lt", lt);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("range", FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode range = root.putObject("frange");
        range.put("query", field);
        if (gte != null) {
            range.set("l", valueNode(gte.value()));
            range.put("incl", true);
        }
        if (gt != null) {
            range.set("l", valueNode(gt.value()));
            range.put("incl", false);
        }
        if (lte != null) {
            range.set("u", valueNode(lte.value()));
            range.put("incu", true);
        }
        if (lt != null) {
            range.set("u", valueNode(lt.value()));
            range.put("incu", false);
        }
        return root;
    }

    private static void putElasticsearchBound(
            ObjectNode fieldRange,
            String boundName,
            RangeBound bound
    ) {
        if (bound != null) {
            fieldRange.set(boundName, valueNode(bound.value()));
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
