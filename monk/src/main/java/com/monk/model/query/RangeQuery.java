package com.monk.model.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.json.QueryNodeDeserializer;
import com.monk.json.QueryPayloadParser;
import com.monk.search.QueryJson;
import com.monk.search.QueryParseContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.AssertTrue;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

@Schema(description = "A range query with lower and upper bounds", oneOf = {RangeQuery.Numeric.class, RangeQuery.Datetime.class})
public sealed interface RangeQuery<T> extends QueryPayload permits RangeQuery.Numeric, RangeQuery.Datetime {
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
        String field = context.requireSearchField("range");
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
        String field = context.requireSearchField("range");
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

    @ApplicationScoped
    class Parser implements QueryPayloadParser {
        private static final Set<String> RANGE_FIELDS = Set.of("type", "gte", "gt", "lte", "lt");
        private static final Set<String> RANGE_BOUND_FIELDS = Set.of("gte", "gt", "lte", "lt");

        @Override
        public String type() {
            return "range";
        }

        @Override
        public RangeQuery<?> parse(JsonParser parser, ObjectMapper mapper, ObjectNode node)
                throws JsonMappingException {
            QueryNodeDeserializer.rejectUnknownFields(parser, node, RANGE_FIELDS, "range query");
            Boolean isNumeric = null;
            for (String field : RANGE_BOUND_FIELDS) {
                JsonNode bound = node.get(field);
                if (bound != null && !bound.isNull()) {
                    if (!bound.isNumber() && !bound.isTextual()) {
                        throw MismatchedInputException.from(parser, Object.class, "Range bound " + field + " must be a number or timestamp");
                    }
                    if (isNumeric != null && isNumeric != bound.isNumber()) {
                        throw MismatchedInputException.from(parser, Object.class, "Range bounds must all be numbers or all be timestamps");
                    }
                    isNumeric = bound.isNumber();
                }
            }
            if (isNumeric == null) {
                throw MismatchedInputException.from(parser, Object.class, "Range query requires at least one bound");
            }
            return isNumeric
                    ? new Numeric(
                        parseDecimal(node.get("gte")),
                        parseDecimal(node.get("gt")),
                        parseDecimal(node.get("lte")),
                        parseDecimal(node.get("lt")))
                    : new Datetime(
                        parseInstant(parser, node.get("gte")),
                        parseInstant(parser, node.get("gt")),
                        parseInstant(parser, node.get("lte")),
                        parseInstant(parser, node.get("lt")));
        }

        private static BigDecimal parseDecimal(JsonNode node) {
            return node == null || node.isNull() ? null : new BigDecimal(node.asText());
        }

        private static Instant parseInstant(JsonParser parser, JsonNode node) throws JsonMappingException {
            if (node == null || node.isNull()) return null;
            try {
                return Instant.parse(node.textValue());
            } catch (DateTimeParseException e) {
                throw MismatchedInputException.from(parser, Object.class, "Range bound must be an ISO 8601 timestamp: " + node.textValue());
            }
        }
    }
}
