package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.model.BooleanQueryData;
import com.monk3.model.ExactQuery;
import com.monk3.model.QueryData;
import com.monk3.model.QueryNode;
import com.monk3.model.QueryPayload;
import com.monk3.model.RangeQuery;
import com.monk3.model.TextQuery;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class QueryNodeDeserializer extends JsonDeserializer<QueryNode> {
    private static final Set<String> EXACT_FIELDS = Set.of("type", "values");
    private static final Set<String> RANGE_FIELDS = Set.of("type", "gte", "gt", "lte", "lt");
    private static final Set<String> RANGE_BOUND_FIELDS = Set.of("gte", "gt", "lte", "lt");

    @Override
    public QueryNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        if (!(node instanceof ObjectNode objectNode)) {
            throw MismatchedInputException.from(parser, Object.class, "Query node must be an object");
        }

        String field = Optional.ofNullable(objectNode.remove("field"))
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .orElseThrow(() -> MismatchedInputException.from(parser, Object.class, "Query node field must be a string"));
        Optional<JsonNode> minimumMatchNode = Optional.ofNullable(objectNode.remove("minimumMatch"))
                .filter(value -> !value.isNull());
        if (minimumMatchNode.isPresent() && !minimumMatchNode.get().canConvertToInt()) {
            throw MismatchedInputException.from(parser, Object.class, "minimumMatch must be an integer");
        }
        Integer minimumMatch = minimumMatchNode.map(JsonNode::intValue).orElse(null);

        Optional<JsonNode> isNotNode = Optional.ofNullable(objectNode.remove("isNot"))
                .filter(value -> !value.isNull());
        if (isNotNode.isPresent() && !isNotNode.get().isBoolean()) {
            throw MismatchedInputException.from(parser, Object.class, "isNot must be a boolean");
        }
        Boolean isNot = isNotNode.map(JsonNode::booleanValue).orElse(null);

        JsonNode dataNode = objectNode.remove("data");
        if (!objectNode.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class, "Unknown query node property: " + objectNode.fieldNames().next());
        }
        if (dataNode == null || dataNode.isNull()) {
            throw MismatchedInputException.from(parser, Object.class, "Query node data is required");
        }

        QueryData data = readData(parser, mapper, field, dataNode);
        return new QueryNode(field, minimumMatch, isNot, data);
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

    private static QueryPayload readPayloadData(JsonParser parser, ObjectMapper mapper, JsonNode node)
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
            default -> throw MismatchedInputException.from(parser, Object.class, unsupportedTypeMessage(typeNode.textValue()));
        };
    }

    private static BooleanQueryData readBooleanData(JsonParser parser, ObjectMapper mapper, JsonNode dataNode)
            throws IOException {
        if (!dataNode.isArray()) {
            throw MismatchedInputException.from(parser, Object.class, "Boolean query node data must be an array");
        }

        List<List<QueryNode>> shouldClauses = new ArrayList<>();
        for (JsonNode shouldClauseNode : dataNode) {
            if (!shouldClauseNode.isArray()) {
                throw MismatchedInputException.from(parser, Object.class, "Boolean query should clauses must be arrays");
            }
            List<QueryNode> mustClauses = new ArrayList<>();
            for (JsonNode mustClauseNode : shouldClauseNode) {
                mustClauses.add(mapper.treeToValue(mustClauseNode, QueryNode.class));
            }
            shouldClauses.add(List.copyOf(mustClauses));
        }
        return new BooleanQueryData(List.copyOf(shouldClauses));
    }

    private static RangeQuery<?> readRange(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, RANGE_FIELDS, "range");
        BoundType type = detectBoundType(parser, node);
        if (type == null) {
            throw MismatchedInputException.from(parser, Object.class, "Range query requires at least one bound");
        }
        return type == BoundType.NUMERIC
                ? new RangeQuery.Numeric(number(node, "gte"), number(node, "gt"), number(node, "lte"), number(node, "lt"))
                : new RangeQuery.Datetime(text(node, "gte"), text(node, "gt"), text(node, "lte"), text(node, "lt"));
    }

    private static ExactQuery<?> readExact(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, EXACT_FIELDS, "exact");
        JsonNode values = node.get("values");
        if (!values.isArray()) {
            throw MismatchedInputException.from(parser, Object.class, "Exact query values must be an array");
        }
        if (values.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class, "Exact query values must not be empty");
        }

        ValueType type = detectValueType(parser, values);
        return switch (type) {
            case NUMERIC -> new ExactQuery.Numeric(values(values, value -> new BigDecimal(value.asText())));
            case DATETIME -> new ExactQuery.Datetime(values(values, JsonNode::textValue));
            case BOOLEAN -> new ExactQuery.BooleanValues(values(values, JsonNode::booleanValue));
        };
    }

    private static boolean hasRangeBound(JsonNode node) {
        return RANGE_BOUND_FIELDS.stream().map(node::get).anyMatch(QueryNodeDeserializer::present);
    }

    private static String unsupportedTypeMessage(String type) {
        return "Unsupported query data type '" + type + "'. Supported query data types are 'text', 'range', and 'exact'.";
    }

    private static void rejectUnknownFields(JsonParser parser, JsonNode node, Set<String> allowed, String queryType)
            throws JsonMappingException {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!allowed.contains(fieldName)) {
                throw MismatchedInputException.from(parser, Object.class, "Unknown " + queryType + " query property: " + fieldName);
            }
        }
    }

    private static BoundType detectBoundType(JsonParser parser, JsonNode node) throws JsonMappingException {
        BoundType detected = null;
        for (String field : RANGE_BOUND_FIELDS) {
            JsonNode bound = node.get(field);
            if (present(bound)) {
                detected = sameType(parser, detected, bound.isNumber() ? BoundType.NUMERIC : bound.isTextual() ? BoundType.DATETIME : null,
                        "Range bound " + field + " must be a number or timestamp",
                        "Range bounds must all be numbers or all be timestamps");
            }
        }
        return detected;
    }

    private static ValueType detectValueType(JsonParser parser, JsonNode values) throws JsonMappingException {
        ValueType detected = null;
        for (JsonNode value : values) {
            if (!present(value)) {
                throw MismatchedInputException.from(parser, Object.class, "Exact query values must not contain null");
            }
            detected = sameType(parser, detected, value.isNumber() ? ValueType.NUMERIC : value.isTextual() ? ValueType.DATETIME : value.isBoolean() ? ValueType.BOOLEAN : null,
                    "Exact query values must be numbers, timestamps, or booleans",
                    "Exact query values must all have the same type");
        }
        return detected;
    }

    private static <T> T sameType(JsonParser parser, T detected, T current, String invalidMessage, String mixedMessage)
            throws JsonMappingException {
        if (current == null) {
            throw MismatchedInputException.from(parser, Object.class, invalidMessage);
        }
        if (detected != null && detected != current) {
            throw MismatchedInputException.from(parser, Object.class, mixedMessage);
        }
        return current;
    }

    private static boolean present(JsonNode value) {
        return value != null && !value.isNull();
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return present(value) ? new BigDecimal(value.asText()) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return present(value) ? value.textValue() : null;
    }

    private static <T> List<T> values(JsonNode values, ValueReader<T> reader) {
        List<T> result = new ArrayList<>();
        values.forEach(value -> result.add(reader.read(value)));
        return List.copyOf(result);
    }

    private enum BoundType { NUMERIC, DATETIME }

    private enum ValueType { NUMERIC, DATETIME, BOOLEAN }

    private interface ValueReader<T> {
        T read(JsonNode value);
    }
}
