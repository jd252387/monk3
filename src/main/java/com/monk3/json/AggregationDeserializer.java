package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.monk3.model.Aggregation;
import com.monk3.model.QueryPayload;
import com.monk3.model.RangeAggregation;
import com.monk3.model.SubfacetsAggregation;
import com.monk3.model.TermsAggregation;
import com.monk3.model.UniqueAggregation;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class AggregationDeserializer extends JsonDeserializer<Aggregation> {
    private static final Set<String> WRAPPER_FIELDS = Set.of("aggType", "args");
    private static final Set<String> TERMS_ARGS = Set.of("field", "size");
    private static final Set<String> UNIQUE_ARGS = Set.of("field");
    private static final Set<String> RANGE_ARGS = Set.of("field", "interval", "from", "to");
    private static final Set<String> SUBFACETS_ARGS = Set.of("field", "filters");

    @Override
    public Aggregation deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        if (!node.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation must be an object");
        }
        rejectUnknownFields(parser, node, WRAPPER_FIELDS, "aggregation");

        JsonNode aggTypeNode = node.get("aggType");
        if (aggTypeNode == null || aggTypeNode.isNull()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation aggType is required");
        }
        if (!aggTypeNode.isTextual()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation aggType must be a string");
        }
        JsonNode args = node.get("args");
        if (args == null || !args.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation args must be an object");
        }
        return switch (aggTypeNode.textValue()) {
            case "terms" -> readTerms(parser, args);
            case "unique" -> readUnique(parser, args);
            case "range" -> readRange(parser, args);
            case "subfacets" -> readSubfacets(parser, mapper, args);
            default -> throw MismatchedInputException.from(parser, Object.class, unsupportedTypeMessage(aggTypeNode.textValue()));
        };
    }

    private static TermsAggregation readTerms(JsonParser parser, JsonNode args) throws JsonMappingException {
        rejectUnknownFields(parser, args, TERMS_ARGS, "terms aggregation");
        Integer size = null;
        JsonNode sizeNode = args.get("size");
        if (sizeNode != null && !sizeNode.isNull()) {
            if (!sizeNode.canConvertToInt()) {
                throw MismatchedInputException.from(parser, Object.class, "Terms aggregation size must be an integer");
            }
            size = sizeNode.intValue();
        }
        return new TermsAggregation(readRequiredField(parser, args), size);
    }

    private static UniqueAggregation readUnique(JsonParser parser, JsonNode args) throws JsonMappingException {
        rejectUnknownFields(parser, args, UNIQUE_ARGS, "unique aggregation");
        return new UniqueAggregation(readRequiredField(parser, args));
    }

    private static RangeAggregation readRange(JsonParser parser, JsonNode args) throws JsonMappingException {
        rejectUnknownFields(parser, args, RANGE_ARGS, "range aggregation");
        String field = readRequiredField(parser, args);
        return new RangeAggregation(
                field,
                requiredNumber(parser, args, "interval"),
                requiredNumber(parser, args, "from"),
                requiredNumber(parser, args, "to"));
    }

    private static SubfacetsAggregation readSubfacets(JsonParser parser, ObjectMapper mapper, JsonNode args)
            throws IOException {
        rejectUnknownFields(parser, args, SUBFACETS_ARGS, "subfacets aggregation");
        String field = readRequiredField(parser, args);
        JsonNode filtersNode = args.get("filters");
        if (filtersNode == null || !filtersNode.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Subfacets aggregation filters must be an object");
        }
        if (filtersNode.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class, "Subfacets aggregation filters must not be empty");
        }
        Map<String, QueryPayload> filters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : filtersNode.properties()) {
            try {
                filters.put(entry.getKey(), QueryNodeDeserializer.readPayloadData(parser, mapper, entry.getValue()));
            } catch (JsonMappingException exception) {
                exception.prependPath(SubfacetsAggregation.class, entry.getKey());
                exception.prependPath(SubfacetsAggregation.class, "filters");
                throw exception;
            }
        }
        return new SubfacetsAggregation(field, Collections.unmodifiableMap(filters));
    }

    private static String readRequiredField(JsonParser parser, JsonNode args) throws JsonMappingException {
        JsonNode fieldNode = args.get("field");
        if (fieldNode == null || fieldNode.isNull()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation args field is required");
        }
        if (!fieldNode.isTextual() || fieldNode.textValue().isBlank()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation args field must be a non-empty string");
        }
        return fieldNode.textValue();
    }

    private static BigDecimal requiredNumber(JsonParser parser, JsonNode args, String property)
            throws JsonMappingException {
        JsonNode value = args.get(property);
        if (value == null || value.isNull()) {
            throw MismatchedInputException.from(parser, Object.class, "Range aggregation requires a numeric '" + property + "'");
        }
        if (!value.isNumber()) {
            throw MismatchedInputException.from(parser, Object.class, "Range aggregation '" + property + "' must be a number");
        }
        return value.decimalValue();
    }

    private static String unsupportedTypeMessage(String aggType) {
        return "Unsupported aggregation type '" + aggType
                + "'. Supported aggregation types are 'terms', 'unique', 'range', and 'subfacets'.";
    }

    private static void rejectUnknownFields(JsonParser parser, JsonNode node, Set<String> allowed, String label)
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
