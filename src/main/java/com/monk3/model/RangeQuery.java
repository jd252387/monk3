package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public record RangeQuery(
        @NotBlank String type,
        RangeBound gte,
        RangeBound gt,
        RangeBound lte,
        RangeBound lt
) implements QueryPayload {
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
        ObjectNode root = context.objectNode();
        ObjectNode range = root.putObject("range");
        ObjectNode fieldRange = range.putObject(field);
        putElasticsearchBound(context, fieldRange, "gte", gte);
        putElasticsearchBound(context, fieldRange, "gt", gt);
        putElasticsearchBound(context, fieldRange, "lte", lte);
        putElasticsearchBound(context, fieldRange, "lt", lt);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("range", FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = context.objectNode();
        ObjectNode range = root.putObject("frange");
        range.put("query", field);
        if (gte != null) {
            range.set("l", context.valueNode(gte.value()));
            range.put("incl", true);
        }
        if (gt != null) {
            range.set("l", context.valueNode(gt.value()));
            range.put("incl", false);
        }
        if (lte != null) {
            range.set("u", context.valueNode(lte.value()));
            range.put("incu", true);
        }
        if (lt != null) {
            range.set("u", context.valueNode(lt.value()));
            range.put("incu", false);
        }
        return root;
    }

    private void putElasticsearchBound(
            QueryParseContext context,
            ObjectNode fieldRange,
            String boundName,
            RangeBound bound
    ) {
        if (bound != null) {
            fieldRange.set(boundName, context.valueNode(bound.value()));
        }
    }
}
