package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk.search.QueryJson;
import com.monk.search.QueryParseContext;
import jakarta.validation.constraints.AssertTrue;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

@Schema(description = "A range query with lower and upper bounds", oneOf = {RangeQuery.Numeric.class, RangeQuery.Datetime.class})
public sealed interface RangeQuery<T> extends QueryPayload permits RangeQuery.Numeric, RangeQuery.Datetime {
    Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.NUMBER, FieldType.DATETIME);

    @JsonProperty
    default String type() {
        return "range";
    }

    T gte();

    T gt();

    T lte();

    T lt();

    default T lowerBound() {
        return gte() != null ? gte() : gt();
    }

    default T upperBound() {
        return lte() != null ? lte() : lt();
    }

    @AssertTrue(message = "range queries allow at most one lower bound and at most one upper bound, and require at least one bound")
    default boolean hasValidBounds() {
        boolean atMostOneLower = gte() == null || gt() == null;
        boolean atMostOneUpper = lte() == null || lt() == null;
        boolean hasAnyBound = gte() != null || gt() != null || lte() != null || lt() != null;
        return atMostOneLower && atMostOneUpper && hasAnyBound;
    }

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("range", SUPPORTED_FIELD_TYPES);
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
        String field = context.requireSearchField("range", SUPPORTED_FIELD_TYPES);
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

    @Schema(description = "A numeric range query (years, counts, etc.)", example = """
            {
              "type": "range",
              "gte": 2020,
              "lte": 2025
            }
            """)
    record Numeric(BigDecimal gte, BigDecimal gt, BigDecimal lte, BigDecimal lt) implements RangeQuery<BigDecimal> {
    }

    @Schema(description = "A datetime range query (ISO-8601 instants)", example = """
            {
              "type": "range",
              "gte": "2024-01-01T00:00:00Z",
              "lt": "2025-01-01T00:00:00Z"
            }
            """)
    record Datetime(Instant gte, Instant gt, Instant lte, Instant lt) implements RangeQuery<Instant> {
    }
}
