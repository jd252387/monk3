package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.model.DatetimeRangeQuery;
import com.monk3.model.NumericRangeQuery;
import com.monk3.model.RangeQuery;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;

public class RangeQueryDeserializer extends JsonDeserializer<RangeQuery> {
    private static final Set<String> ALLOWED_FIELDS = Set.of("type", "gte", "gt", "lte", "lt");
    private static final Set<String> BOUND_FIELDS = Set.of("gte", "gt", "lte", "lt");

    @Override
    public RangeQuery deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (!node.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Range query must be an object");
        }
        rejectUnknownFields(parser, node);

        validateDiscriminator(parser, node.get("type"));

        BoundType boundType = detectBoundType(parser, node);
        if (boundType == null) {
            throw MismatchedInputException.from(parser, Object.class, "Range query requires at least one bound");
        }

        return boundType == BoundType.NUMERIC
                ? new NumericRangeQuery(
                numericBound(node.get("gte")),
                numericBound(node.get("gt")),
                numericBound(node.get("lte")),
                numericBound(node.get("lt"))
        )
                : new DatetimeRangeQuery(
                datetimeBound(node.get("gte")),
                datetimeBound(node.get("gt")),
                datetimeBound(node.get("lte")),
                datetimeBound(node.get("lt"))
        );
    }

    private static void rejectUnknownFields(JsonParser parser, JsonNode node) throws JsonMappingException {
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw MismatchedInputException.from(parser, Object.class, "Unknown range query property: " + fieldName);
            }
        }
    }

    private static void validateDiscriminator(JsonParser parser, JsonNode typeNode) throws JsonMappingException {
        if (typeNode == null || typeNode.isNull()) {
            return;
        }
        if (!typeNode.isTextual() || !"range".equals(typeNode.textValue())) {
            throw MismatchedInputException.from(parser, Object.class, "Range query type must be range");
        }
    }

    private static BoundType detectBoundType(JsonParser parser, JsonNode node) throws JsonMappingException {
        BoundType detectedType = null;
        Iterator<String> fieldNames = BOUND_FIELDS.iterator();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode boundNode = node.get(fieldName);
            if (boundNode == null || boundNode.isNull()) {
                continue;
            }

            BoundType currentType = boundType(parser, fieldName, boundNode);
            if (detectedType != null && detectedType != currentType) {
                throw MismatchedInputException.from(parser, Object.class, "Range bounds must all be numbers or all be timestamps");
            }
            detectedType = currentType;
        }
        return detectedType;
    }

    private static BoundType boundType(JsonParser parser, String fieldName, JsonNode boundNode)
            throws JsonMappingException {
        if (boundNode.isNumber()) {
            return BoundType.NUMERIC;
        }
        if (boundNode.isTextual()) {
            return BoundType.DATETIME;
        }
        throw MismatchedInputException.from(parser, Object.class, "Range bound " + fieldName + " must be a number or timestamp");
    }

    private static BigDecimal numericBound(JsonNode boundNode) {
        if (boundNode == null || boundNode.isNull()) {
            return null;
        }
        return new BigDecimal(boundNode.asText());
    }

    private static String datetimeBound(JsonNode boundNode) {
        if (boundNode == null || boundNode.isNull()) {
            return null;
        }
        return boundNode.textValue();
    }

    private enum BoundType {
        NUMERIC,
        DATETIME
    }
}
