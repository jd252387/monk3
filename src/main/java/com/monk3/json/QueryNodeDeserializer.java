package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.model.BooleanQueryData;
import com.monk3.model.QueryData;
import com.monk3.model.QueryNode;
import com.monk3.model.QueryPayload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class QueryNodeDeserializer extends JsonDeserializer<QueryNode> {
    private static final Set<String> ALLOWED_FIELDS = Set.of("field", "minimumMatch", "isNot", "data");

    @Override
    public QueryNode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        if (!(node instanceof ObjectNode objectNode)) {
            throw JsonMappingException.from(parser, "Query node must be an object");
        }
        rejectUnknownFields(parser, objectNode);

        JsonNode fieldNode = objectNode.get("field");
        if (fieldNode == null || fieldNode.isNull() || !fieldNode.isTextual()) {
            throw JsonMappingException.from(parser, "Query node field must be a string");
        }

        String field = fieldNode.textValue();
        JsonNode minimumMatchNode = objectNode.get("minimumMatch");
        Integer minimumMatch = null;
        if (minimumMatchNode != null && !minimumMatchNode.isNull()) {
            if (!minimumMatchNode.canConvertToInt()) {
                throw JsonMappingException.from(parser, "minimumMatch must be an integer");
            }
            minimumMatch = minimumMatchNode.intValue();
        }

        JsonNode isNotNode = objectNode.get("isNot");
        Boolean isNot = null;
        if (isNotNode != null && !isNotNode.isNull()) {
            if (!isNotNode.isBoolean()) {
                throw JsonMappingException.from(parser, "isNot must be a boolean");
            }
            isNot = isNotNode.booleanValue();
        }

        JsonNode dataNode = objectNode.get("data");
        if (dataNode == null || dataNode.isNull()) {
            throw JsonMappingException.from(parser, "Query node data is required");
        }

        QueryData data = field.isEmpty()
                ? readBooleanData(parser, mapper, dataNode)
                : mapper.treeToValue(dataNode, QueryPayload.class);
        return new QueryNode(field, minimumMatch, isNot, data);
    }

    private static void rejectUnknownFields(JsonParser parser, ObjectNode objectNode) throws JsonMappingException {
        Iterator<String> fieldNames = objectNode.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                throw JsonMappingException.from(parser, "Unknown query node property: " + fieldName);
            }
        }
    }

    private static BooleanQueryData readBooleanData(JsonParser parser, ObjectMapper mapper, JsonNode dataNode)
            throws IOException {
        if (!dataNode.isArray()) {
            throw JsonMappingException.from(parser, "Boolean query node data must be an array");
        }

        List<List<QueryNode>> shouldClauses = new ArrayList<>();
        for (JsonNode shouldClauseNode : dataNode) {
            if (!shouldClauseNode.isArray()) {
                throw JsonMappingException.from(parser, "Boolean query should clauses must be arrays");
            }
            List<QueryNode> mustClauses = new ArrayList<>();
            for (JsonNode mustClauseNode : shouldClauseNode) {
                mustClauses.add(mapper.treeToValue(mustClauseNode, QueryNode.class));
            }
            shouldClauses.add(List.copyOf(mustClauses));
        }
        return new BooleanQueryData(List.copyOf(shouldClauses));
    }
}
