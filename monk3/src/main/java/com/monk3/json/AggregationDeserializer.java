package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.monk3.model.Aggregation;
import com.monk3.model.AvgAggregation;
import com.monk3.model.FilterAggregation;
import com.monk3.model.MaxAggregation;
import com.monk3.model.MinAggregation;
import com.monk3.model.QueryNode;
import com.monk3.model.QueryPayload;
import com.monk3.model.RangeAggregation;
import com.monk3.model.SubfacetsAggregation;
import com.monk3.model.SumAggregation;
import com.monk3.model.TermsAggregation;
import com.monk3.model.UniqueAggregation;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.monk3.json.QueryNodeDeserializer.rejectUnknownFields;

public class AggregationDeserializer extends JsonDeserializer<Aggregation> {
    private static final Set<String> WRAPPER_FIELDS = Set.of("aggType", "args", "aggs");
    private static final Set<String> TERMS_ARGS = Set.of("field", "size");
    private static final Set<String> UNIQUE_ARGS = Set.of("field");
    private static final Set<String> METRIC_ARGS = Set.of("field");
    private static final Set<String> RANGE_ARGS = Set.of("field", "interval", "from", "to");
    private static final Set<String> SUBFACETS_ARGS = Set.of("field", "filters");
    private static final Set<String> FILTER_ARGS = Set.of("query");

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
        Map<String, Aggregation> subAggregations = readSubAggregations(parser, mapper, node.get("aggs"));
        String aggType = aggTypeNode.textValue();
        return switch (aggType) {
            case "terms" -> readTerms(parser, args, subAggregations);
            case "range" -> readRange(parser, args, subAggregations);
            case "subfacets" -> readSubfacets(parser, mapper, args, subAggregations);
            case "filter" -> readFilter(parser, mapper, args, subAggregations);
            case "unique" -> {
                rejectSubAggregations(parser, subAggregations, aggType);
                yield readUnique(parser, args);
            }
            case "sum" -> {
                rejectSubAggregations(parser, subAggregations, aggType);
                yield new SumAggregation(readMetricField(parser, args, "sum aggregation"));
            }
            case "avg" -> {
                rejectSubAggregations(parser, subAggregations, aggType);
                yield new AvgAggregation(readMetricField(parser, args, "avg aggregation"));
            }
            case "min" -> {
                rejectSubAggregations(parser, subAggregations, aggType);
                yield new MinAggregation(readMetricField(parser, args, "min aggregation"));
            }
            case "max" -> {
                rejectSubAggregations(parser, subAggregations, aggType);
                yield new MaxAggregation(readMetricField(parser, args, "max aggregation"));
            }
            default -> throw MismatchedInputException.from(parser, Object.class, unsupportedTypeMessage(aggType));
        };
    }

    private static Map<String, Aggregation> readSubAggregations(JsonParser parser, ObjectMapper mapper, JsonNode aggsNode)
            throws IOException {
        if (aggsNode == null || aggsNode.isNull()) {
            return Map.of();
        }
        if (!aggsNode.isObject()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation aggs must be an object");
        }
        if (aggsNode.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class, "Aggregation aggs must not be empty");
        }
        Map<String, Aggregation> subAggregations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : aggsNode.properties()) {
            try {
                subAggregations.put(entry.getKey(), mapper.treeToValue(entry.getValue(), Aggregation.class));
            } catch (JsonMappingException exception) {
                exception.prependPath(Aggregation.class, entry.getKey());
                exception.prependPath(Aggregation.class, "aggs");
                throw exception;
            }
        }
        return Collections.unmodifiableMap(subAggregations);
    }

    private static void rejectSubAggregations(JsonParser parser, Map<String, Aggregation> subAggregations, String aggType)
            throws JsonMappingException {
        if (!subAggregations.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class,
                    "Aggregation type '" + aggType + "' does not support sub-aggregations");
        }
    }

    private static TermsAggregation readTerms(JsonParser parser, JsonNode args, Map<String, Aggregation> subAggregations)
            throws JsonMappingException {
        rejectUnknownFields(parser, args, TERMS_ARGS, "terms aggregation");
        Integer size = null;
        JsonNode sizeNode = args.get("size");
        if (sizeNode != null && !sizeNode.isNull()) {
            if (!sizeNode.canConvertToInt()) {
                throw MismatchedInputException.from(parser, Object.class, "Terms aggregation size must be an integer");
            }
            size = sizeNode.intValue();
        }
        return new TermsAggregation(readRequiredField(parser, args), size, subAggregations);
    }

    private static UniqueAggregation readUnique(JsonParser parser, JsonNode args) throws JsonMappingException {
        rejectUnknownFields(parser, args, UNIQUE_ARGS, "unique aggregation");
        return new UniqueAggregation(readRequiredField(parser, args));
    }

    private static String readMetricField(JsonParser parser, JsonNode args, String aggregation)
            throws JsonMappingException {
        rejectUnknownFields(parser, args, METRIC_ARGS, aggregation);
        return readRequiredField(parser, args);
    }

    private static RangeAggregation readRange(JsonParser parser, JsonNode args, Map<String, Aggregation> subAggregations)
            throws JsonMappingException {
        rejectUnknownFields(parser, args, RANGE_ARGS, "range aggregation");
        String field = readRequiredField(parser, args);
        return new RangeAggregation(
                field,
                requiredNumber(parser, args, "interval"),
                requiredNumber(parser, args, "from"),
                requiredNumber(parser, args, "to"),
                subAggregations);
    }

    private static SubfacetsAggregation readSubfacets(
            JsonParser parser, ObjectMapper mapper, JsonNode args, Map<String, Aggregation> subAggregations)
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
        return new SubfacetsAggregation(field, Collections.unmodifiableMap(filters), subAggregations);
    }

    private static FilterAggregation readFilter(
            JsonParser parser, ObjectMapper mapper, JsonNode args, Map<String, Aggregation> subAggregations)
            throws IOException {
        rejectUnknownFields(parser, args, FILTER_ARGS, "filter aggregation");
        JsonNode queryNode = args.get("query");
        if (queryNode == null || !queryNode.isArray()) {
            throw MismatchedInputException.from(parser, Object.class, "Filter aggregation query must be an array");
        }
        if (queryNode.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class, "Filter aggregation query must not be empty");
        }
        List<QueryNode> nodes = new ArrayList<>();
        for (JsonNode element : queryNode) {
            try {
                nodes.add(QueryNodeDeserializer.readNode(parser, mapper, element));
            } catch (JsonMappingException exception) {
                exception.prependPath(FilterAggregation.class, "query");
                throw exception;
            }
        }
        return new FilterAggregation(List.copyOf(nodes), subAggregations);
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
                + "'. Supported aggregation types are 'terms', 'unique', 'range', 'subfacets', 'filter', 'sum', 'avg',"
                + " 'min', and 'max'.";
    }
}
