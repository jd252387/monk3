package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.model.BooleanExactQuery;
import com.monk3.model.DatetimeExactQuery;
import com.monk3.model.ExactQuery;
import com.monk3.model.NumericExactQuery;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class ExactQueryDeserializer extends JsonDeserializer<ExactQuery> {
    private static final Set<String> ALLOWED_FIELDS = Set.of("values");

    @Override
    public ExactQuery deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (!node.isObject()) {
            throw JsonMappingException.from(parser, "Exact query must be an object");
        }
        rejectUnknownFields(parser, node);

        JsonNode valuesNode = node.get("values");
        if (valuesNode == null || valuesNode.isNull()) {
            throw JsonMappingException.from(parser, "Exact query values are required");
        }
        if (!valuesNode.isArray()) {
            throw JsonMappingException.from(parser, "Exact query values must be an array");
        }
        if (valuesNode.isEmpty()) {
            throw JsonMappingException.from(parser, "Exact query values must not be empty");
        }

        ValueType valueType = detectValueType(parser, valuesNode);
        return switch (valueType) {
            case NUMERIC -> new NumericExactQuery(numericValues(valuesNode));
            case DATETIME -> new DatetimeExactQuery(datetimeValues(valuesNode));
            case BOOLEAN -> new BooleanExactQuery(booleanValues(valuesNode));
        };
    }

    private static void rejectUnknownFields(JsonParser parser, JsonNode node) throws JsonMappingException {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(parser, "Unknown exact query property: " + fieldName);
            }
        }
    }

    private static ValueType detectValueType(JsonParser parser, JsonNode valuesNode) throws JsonMappingException {
        ValueType detectedType = null;
        for (JsonNode valueNode : valuesNode) {
            if (valueNode == null || valueNode.isNull()) {
                throw JsonMappingException.from(parser, "Exact query values must not contain null");
            }

            ValueType currentType = valueType(parser, valueNode);
            if (detectedType != null && detectedType != currentType) {
                throw JsonMappingException.from(parser, "Exact query values must all have the same type");
            }
            detectedType = currentType;
        }
        return detectedType;
    }

    private static ValueType valueType(JsonParser parser, JsonNode valueNode) throws JsonMappingException {
        if (valueNode.isNumber()) {
            return ValueType.NUMERIC;
        }
        if (valueNode.isTextual()) {
            return ValueType.DATETIME;
        }
        if (valueNode.isBoolean()) {
            return ValueType.BOOLEAN;
        }
        throw JsonMappingException.from(parser, "Exact query values must be numbers, timestamps, or booleans");
    }

    private static List<BigDecimal> numericValues(JsonNode valuesNode) {
        List<BigDecimal> values = new ArrayList<>();
        for (JsonNode valueNode : valuesNode) {
            values.add(new BigDecimal(valueNode.asText()));
        }
        return List.copyOf(values);
    }

    private static List<String> datetimeValues(JsonNode valuesNode) {
        List<String> values = new ArrayList<>();
        for (JsonNode valueNode : valuesNode) {
            values.add(valueNode.textValue());
        }
        return List.copyOf(values);
    }

    private static List<Boolean> booleanValues(JsonNode valuesNode) {
        List<Boolean> values = new ArrayList<>();
        for (JsonNode valueNode : valuesNode) {
            values.add(valueNode.booleanValue());
        }
        return List.copyOf(values);
    }

    private enum ValueType {
        NUMERIC,
        DATETIME,
        BOOLEAN
    }
}
