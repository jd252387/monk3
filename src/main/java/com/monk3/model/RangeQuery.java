package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.AssertTrue;

import java.math.BigDecimal;
import java.time.Instant;

public sealed interface RangeQuery<T> extends QueryPayload permits RangeQuery.Numeric, RangeQuery.Datetime {
    @JsonProperty
    default String type() {
        return "range";
    }

    T gte();

    T gt();

    T lte();

    T lt();

    @AssertTrue(message = "range queries require exactly one lower bound and exactly one upper bound")
    default boolean hasValidBounds() {
        return (gte() != null) != (gt() != null) && (lte() != null) != (lt() != null);
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
            range.set("l", QueryJson.valueNode(gte()));
            range.put("incl", true);
        }
        if (gt() != null) {
            range.set("l", QueryJson.valueNode(gt()));
            range.put("incl", false);
        }
        if (lte() != null) {
            range.set("u", QueryJson.valueNode(lte()));
            range.put("incu", true);
        }
        if (lt() != null) {
            range.set("u", QueryJson.valueNode(lt()));
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
            fieldRange.set(boundName, QueryJson.valueNode(bound));
        }
    }

    record Numeric(BigDecimal gte, BigDecimal gt, BigDecimal lte, BigDecimal lt) implements RangeQuery<BigDecimal> {
    }

    record Datetime(Instant gte, Instant gt, Instant lte, Instant lt) implements RangeQuery<Instant> {
    }
}
