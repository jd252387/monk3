package com.monk.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.monk.model.Aggregation;
import com.monk.model.FilterAggregation;
import com.monk.model.MetricAggregation;
import com.monk.model.NestedAggregation;
import com.monk.model.QueryNode;
import com.monk.model.QueryPayload;
import com.monk.model.RangeAggregation;
import com.monk.model.SubfacetsAggregation;
import com.monk.model.TermsAggregation;
import com.monk.model.UniqueAggregation;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.monk.json.QueryNodeDeserializer.rejectUnknownFields;

@Singleton
public class AggregationDeserializer extends JsonDeserializer<Aggregation> {
    private static final Set<String> WRAPPER_FIELDS = Set.of("aggType", "args", "aggs");
    private static final Set<String> TERMS_ARGS = Set.of("field", "size");
    private static final Set<String> UNIQUE_ARGS = Set.of("field");
    private static final Set<String> METRIC_ARGS = Set.of("field");
    private static final Set<String> RANGE_ARGS = Set.of("field", "interval", "from", "to");
    private static final Set<String> SUBFACETS_ARGS = Set.of("field", "filters");
    private static final Set<String> FILTER_ARGS = Set.of("query");
    private static final Set<String> NESTED_ARGS = Set.of("path");

    private final QueryNodeDeserializer queryNodeDeserializer;

    AggregationDeserializer(QueryNodeDeserializer queryNodeDeserializer) {
        this.queryNodeDeserializer = queryNodeDeserializer;
    }

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
            case "nested" -> readNested(parser, args, subAggregations);
            case "unique" -> {
                rejectSubAggregations(parser, subAggregations, aggType);
                yield readUnique(parser, args);
            }
            case "sum" -> readMetric(MetricAggregation.Metric.SUM, parser, args, subAggregations);
            case "avg" -> readMetric(MetricAggregation.Metric.AVG, parser, args, subAggregations);
            case "min" -> readMetric(MetricAggregation.Metric.MIN, parser, args, subAggregations);
            case "max" -> readMetric(MetricAggregation.Metric.MAX, parser, args, subAggregations);
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

    private static MetricAggregation readMetric(
            MetricAggregation.Metric metric, JsonParser parser, JsonNode args, Map<String, Aggregation> subAggregations)
            throws JsonMappingException {
        rejectSubAggregations(parser, subAggregations, metric.aggType());
        return new MetricAggregation(metric, readMetricField(parser, args, metric.aggType() + " aggregation"));
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

    private SubfacetsAggregation readSubfacets(
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
                filters.put(entry.getKey(), queryNodeDeserializer.readPayloadData(parser, mapper, entry.getValue()));
            } catch (JsonMappingException exception) {
                exception.prependPath(SubfacetsAggregation.class, entry.getKey());
                exception.prependPath(SubfacetsAggregation.class, "filters");
                throw exception;
            }
        }
        return new SubfacetsAggregation(field, Collections.unmodifiableMap(filters), subAggregations);
    }

    private FilterAggregation readFilter(
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
                nodes.add(queryNodeDeserializer.readNode(parser, mapper, element));
            } catch (JsonMappingException exception) {
                exception.prependPath(FilterAggregation.class, "query");
                throw exception;
            }
        }
        return new FilterAggregation(List.copyOf(nodes), subAggregations);
    }

    private static NestedAggregation readNested(
            JsonParser parser, JsonNode args, Map<String, Aggregation> subAggregations) throws JsonMappingException {
        rejectUnknownFields(parser, args, NESTED_ARGS, "nested aggregation");
        if (subAggregations.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class,
                    "Nested aggregation requires one or more sub-aggregations");
        }
        return new NestedAggregation(readRequiredPath(parser, args), subAggregations);
    }

    private static List<String> readRequiredPath(JsonParser parser, JsonNode args) throws JsonMappingException {
        JsonNode pathNode = args.get("path");
        if (pathNode == null || !pathNode.isArray() || pathNode.isEmpty()) {
            throw MismatchedInputException.from(parser, Object.class,
                    "Nested aggregation args path must be a non-empty array of strings");
        }
        List<String> path = new ArrayList<>();
        for (JsonNode element : pathNode) {
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw MismatchedInputException.from(parser, Object.class,
                        "Nested aggregation args path must contain only non-empty strings");
            }
            path.add(element.textValue());
        }
        return List.copyOf(path);
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
                + " 'min', 'max', and 'nested'.";
    }
}
