package com.monk.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.BooleanOccur;
import com.monk.model.BooleanQueryData;
import com.monk.model.ExactQuery;
import com.monk.model.ExistsQuery;
import com.monk.model.KnnFlatQuery;
import com.monk.model.PrefixQuery;
import com.monk.model.QueryData;
import com.monk.model.QueryNode;
import com.monk.model.QueryPayload;
import com.monk.model.RangeQuery;
import com.monk.model.TextQuery;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class QueryNodeDeserializer extends JsonDeserializer<QueryNode> {
    private static final Set<String> NODE_FIELDS = Set.of("field", "minimumMatch", "bool", "data");
    private static final Set<String> EXACT_FIELDS = Set.of("type", "values");
    private static final Set<String> EXISTS_FIELDS = Set.of("type");
    private static final Set<String> PREFIX_FIELDS = Set.of("type", "prefix");
    private static final Set<String> KNN_FLAT_FIELDS = Set.of("type", "text", "k");
    private static final Set<String> RANGE_FIELDS = Set.of("type", "gte", "gt", "lte", "lt");
    private static final Set<String> RANGE_BOUND_FIELDS = Set.of("gte", "gt", "lte", "lt");

    @Override
    public QueryNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        return readNode(parser, mapper, mapper.readTree(parser));
    }

    static QueryNode readNode(JsonParser parser, ObjectMapper mapper, JsonNode node) throws IOException {
        if (!(node instanceof ObjectNode objectNode)) {
            throw MismatchedInputException.from(parser, Object.class, "Query node must be an object");
        }

        JsonNode fieldNode = objectNode.get("field");
        if (fieldNode == null || !fieldNode.isTextual()) {
            throw MismatchedInputException.from(parser, Object.class, "Query node field must be a string");
        }
        String field = fieldNode.textValue();

        JsonNode minimumMatchNode = objectNode.get("minimumMatch");
        Integer minimumMatch = null;
        if (minimumMatchNode != null && !minimumMatchNode.isNull()) {
            if (!minimumMatchNode.canConvertToInt()) {
                throw MismatchedInputException.from(parser, Object.class, "minimumMatch must be an integer");
            }
            minimumMatch = minimumMatchNode.intValue();
        }

        BooleanOccur bool = readBool(parser, objectNode.get("bool"));

        rejectUnknownFields(parser, objectNode, NODE_FIELDS, "query node");

        JsonNode dataNode = objectNode.get("data");
        if (dataNode == null || dataNode.isNull()) {
            if (field.isEmpty()) {
                throw MismatchedInputException.from(parser, Object.class, "Query node data is required");
            }
            // A leaf node without data is only valid for predicate virtual fields; whether
            // the field actually resolves to a predicate is checked at translation time.
            return new QueryNode(field, minimumMatch, bool, null);
        }

        QueryData data = readData(parser, mapper, field, dataNode);
        return new QueryNode(field, minimumMatch, bool, data);
    }

    private static BooleanOccur readBool(JsonParser parser, JsonNode boolNode) throws JsonMappingException {
        if (boolNode == null || boolNode.isNull()) {
            return null;
        }
        if (!boolNode.isTextual()) {
            throw MismatchedInputException.from(parser, Object.class, "bool must be a string");
        }
        return switch (boolNode.textValue()) {
            case "should" -> BooleanOccur.SHOULD;
            case "must" -> BooleanOccur.MUST;
            case "mustNot" -> BooleanOccur.MUST_NOT;
            default -> throw MismatchedInputException.from(parser, Object.class,
                    "bool must be one of 'should', 'must', or 'mustNot'");
        };
    }

    private static QueryData readData(
            JsonParser parser,
            ObjectMapper mapper,
            String field,
            JsonNode dataNode
    ) throws IOException {
        try {
            return field.isEmpty() || dataNode.isArray()
                    ? readBooleanData(parser, mapper, dataNode)
                    : readPayloadData(parser, mapper, dataNode);
        } catch (JsonMappingException exception) {
            exception.prependPath(QueryNode.class, "data");
            throw exception;
        }
    }

    static QueryPayload readPayloadData(JsonParser parser, ObjectMapper mapper, JsonNode node)
            throws IOException {
        if (!node.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Query payload must be an object");
        }

        JsonNode typeNode = node.get("type");
        if (typeNode == null || typeNode.isNull()) {
            throw MismatchedInputException.from(parser, Object.class, "Query payload type is required");
        }
        if (!typeNode.isTextual()) {
            throw MismatchedInputException.from(parser, Object.class, "Query payload type must be a string");
        }
        return switch (typeNode.textValue()) {
            case "text" -> mapper.treeToValue(node, TextQuery.class);
            case "range" -> readRange(parser, node);
            case "exact" -> readExact(parser, node);
            case "exists" -> readExists(parser, node);
            case "prefix" -> readPrefix(parser, node);
            case "knnFlat" -> readKnnFlat(parser, node);
            default -> throw MismatchedInputException.from(parser, Object.class, unsupportedTypeMessage(typeNode.textValue()));
        };
    }

    private static BooleanQueryData readBooleanData(JsonParser parser, ObjectMapper mapper, JsonNode dataNode)
            throws IOException {
        if (!dataNode.isArray()) {
            throw MismatchedInputException.from(parser, Object.class, "Boolean query node data must be an array");
        }

        List<QueryNode> clauses = new ArrayList<>();
        for (JsonNode clauseNode : dataNode) {
            QueryNode clause = readNode(parser, mapper, clauseNode);
            if (clause.bool() == null) {
                throw MismatchedInputException.from(parser, Object.class,
                        "Each boolean query clause requires a 'bool' of 'should', 'must', or 'mustNot'");
            }
            clauses.add(clause);
        }
        return new BooleanQueryData(List.copyOf(clauses));
    }

    private static RangeQuery<?> readRange(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, RANGE_FIELDS, "range query");
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
                ? new RangeQuery.Numeric(
                    parseDecimal(node.get("gte")),
                    parseDecimal(node.get("gt")),
                    parseDecimal(node.get("lte")),
                    parseDecimal(node.get("lt")))
                : new RangeQuery.Datetime(
                    parseInstant(parser, node.get("gte")),
                    parseInstant(parser, node.get("gt")),
                    parseInstant(parser, node.get("lte")),
                    parseInstant(parser, node.get("lt")));
    }

    private static ExactQuery<?> readExact(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, EXACT_FIELDS, "exact query");
        JsonNode values = node.get("values");
        if (!values.isArray()) {
            throw MismatchedInputException.from(parser, Object.class, "Exact query values must be an array");
        }
        if (values.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class, "Exact query values must not be empty");
        }

        List<BigDecimal> numerics = new ArrayList<>();
        List<String> datetimes = new ArrayList<>();
        List<Boolean> booleans = new ArrayList<>();
        for (JsonNode value : values) {
            if (value == null || value.isNull()) {
                throw MismatchedInputException.from(parser, Object.class, "Exact query values must not contain null");
            }
            if (value.isNumber()) numerics.add(new BigDecimal(value.asText()));
            else if (value.isTextual()) datetimes.add(value.textValue());
            else if (value.isBoolean()) booleans.add(value.booleanValue());
            else throw MismatchedInputException.from(parser, Object.class, "Exact query values must be numbers, timestamps, or booleans");
        }
        if ((!numerics.isEmpty() ? 1 : 0) + (!datetimes.isEmpty() ? 1 : 0) + (!booleans.isEmpty() ? 1 : 0) > 1) {
            throw MismatchedInputException.from(parser, Object.class, "Exact query values must all have the same type");
        }
        if (!numerics.isEmpty()) return new ExactQuery.Numeric(List.copyOf(numerics));
        if (!datetimes.isEmpty()) return new ExactQuery.Datetime(List.copyOf(datetimes));
        return new ExactQuery.BooleanValues(List.copyOf(booleans));
    }

    private static ExistsQuery readExists(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, EXISTS_FIELDS, "exists query");
        return new ExistsQuery();
    }

    private static PrefixQuery readPrefix(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, PREFIX_FIELDS, "prefix query");
        JsonNode prefix = node.get("prefix");
        if (prefix == null || prefix.isNull() || !prefix.isTextual() || prefix.textValue().isBlank()) {
            throw MismatchedInputException.from(parser, Object.class, "Prefix query requires a non-empty 'prefix' string");
        }
        return new PrefixQuery(prefix.textValue());
    }

    private static KnnFlatQuery readKnnFlat(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, KNN_FLAT_FIELDS, "knnFlat query");
        JsonNode text = node.get("text");
        if (text == null || text.isNull() || !text.isTextual() || text.textValue().isBlank()) {
            throw MismatchedInputException.from(parser, Object.class, "knnFlat query requires a non-empty 'text' string");
        }
        JsonNode kNode = node.get("k");
        Integer k = null;
        if (kNode != null && !kNode.isNull()) {
            if (!kNode.canConvertToInt() || kNode.intValue() < 1) {
                throw MismatchedInputException.from(parser, Object.class, "knnFlat query 'k' must be a positive integer");
            }
            k = kNode.intValue();
        }
        return new KnnFlatQuery(text.textValue(), k);
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

    private static String unsupportedTypeMessage(String type) {
        return "Unsupported query data type '" + type + "'. Supported query data types are 'text', 'range', 'exact', 'exists', 'prefix', and 'knnFlat'.";
    }

    static void rejectUnknownFields(JsonParser parser, JsonNode node, Set<String> allowed, String label)
            throws JsonMappingException {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!allowed.contains(fieldName)) {
                throw MismatchedInputException.from(parser, Object.class, "Unknown " + label + " property: " + fieldName);
            }
        }
    }
}
