package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monk3.model.ExactQuery;
import com.monk3.model.QueryPayload;
import com.monk3.model.RangeQuery;
import com.monk3.model.TextQuery;

import java.io.IOException;
import java.util.Set;

public class QueryPayloadDeserializer extends JsonDeserializer<QueryPayload> {
    private static final Set<String> RANGE_BOUND_FIELDS = Set.of("gte", "gt", "lte", "lt");

    @Override
    public QueryPayload deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        if (!node.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Query payload must be an object");
        }

        JsonNode typeNode = node.get("type");
        if (typeNode != null && !typeNode.isNull()) {
            if (!typeNode.isTextual()) {
                throw MismatchedInputException.from(parser, Object.class, "Query payload type must be a string");
            }
            return switch (typeNode.textValue()) {
                case "text" -> mapper.treeToValue(node, TextQuery.class);
                case "range" -> readRange(context, mapper, node);
                default -> throw MismatchedInputException.from(parser, Object.class, unsupportedTypeMessage(typeNode.textValue()));
            };
        }

        if (hasRangeBound(node)) {
            return readRange(context, mapper, node);
        }
        if (hasValues(node)) {
            return readExact(context, mapper, node);
        }
        throw MismatchedInputException.from(parser, Object.class, "Query payload type is required unless range bounds or exact values are provided");
    }

    private static RangeQuery<?> readRange(DeserializationContext context, ObjectMapper mapper, JsonNode node)
            throws IOException {
        JsonParser rangeParser = node.traverse(mapper);
        rangeParser.nextToken();
        return new RangeQueryDeserializer().deserialize(rangeParser, context);
    }

    private static ExactQuery<?> readExact(DeserializationContext context, ObjectMapper mapper, JsonNode node)
            throws IOException {
        JsonParser exactParser = node.traverse(mapper);
        exactParser.nextToken();
        return new ExactQueryDeserializer().deserialize(exactParser, context);
    }

    private static boolean hasRangeBound(JsonNode node) {
        for (String boundField : RANGE_BOUND_FIELDS) {
            JsonNode boundNode = node.get(boundField);
            if (boundNode != null && !boundNode.isNull()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValues(JsonNode node) {
        JsonNode valuesNode = node.get("values");
        return valuesNode != null && !valuesNode.isNull();
    }

    private static String unsupportedTypeMessage(String type) {
        return "Unsupported query data type '" + type + "'. Supported query data types are 'text' and 'range'.";
    }
}
