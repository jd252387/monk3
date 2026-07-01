package com.monk.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.query.BooleanOccur;
import com.monk.model.query.BooleanQueryData;
import com.monk.model.query.QueryData;
import com.monk.model.query.QueryNode;
import com.monk.model.query.QueryPayload;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Singleton
public class QueryNodeDeserializer extends JsonDeserializer<QueryNode> {
    private static final Set<String> NODE_FIELDS = Set.of("field", "minimumMatch", "bool", "data", "filtering");

    private final QueryPayloadRegistry payloadRegistry;

    QueryNodeDeserializer(QueryPayloadRegistry payloadRegistry) {
        this.payloadRegistry = payloadRegistry;
    }

    @Override
    public QueryNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        return readNode(parser, mapper, mapper.readTree(parser));
    }

    QueryNode readNode(JsonParser parser, ObjectMapper mapper, JsonNode node) throws IOException {
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

        boolean filtering = readFiltering(parser, objectNode.get("filtering"));

        rejectUnknownFields(parser, objectNode, NODE_FIELDS, "query node");

        JsonNode dataNode = objectNode.get("data");
        if (dataNode == null || dataNode.isNull()) {
            if (field.isEmpty()) {
                throw MismatchedInputException.from(parser, Object.class, "Query node data is required");
            }
            // A leaf node without data is only valid for predicate virtual fields; whether
            // the field actually resolves to a predicate is checked at translation time.
            return new QueryNode(field, minimumMatch, bool, null, filtering);
        }

        QueryData data = readData(parser, mapper, field, dataNode);
        return new QueryNode(field, minimumMatch, bool, data, filtering);
    }

    private static boolean readFiltering(JsonParser parser, JsonNode filteringNode) throws JsonMappingException {
        if (filteringNode == null || filteringNode.isNull()) {
            return false;
        }
        if (!filteringNode.isBoolean()) {
            throw MismatchedInputException.from(parser, Object.class, "filtering must be a boolean");
        }
        return filteringNode.booleanValue();
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

    private QueryData readData(
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

    QueryPayload readPayloadData(JsonParser parser, ObjectMapper mapper, JsonNode node)
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
        return payloadRegistry.parse(parser, mapper, (ObjectNode) node, typeNode.textValue());
    }

    private BooleanQueryData readBooleanData(JsonParser parser, ObjectMapper mapper, JsonNode dataNode)
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

    public static void rejectUnknownFields(JsonParser parser, JsonNode node, Set<String> allowed, String label)
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
