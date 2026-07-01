package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.json.QueryNodeDeserializer;
import com.monk.json.QueryPayloadParser;
import com.monk.search.QueryParseContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Schema(description = "An exists query matching documents where the field has any value", example = """
        {
          "type": "exists"
        }
        """)
public record ExistsQuery() implements QueryPayload {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    public String type() {
        return "exists";
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exists");
        ObjectNode root = JSON.objectNode();
        root.putObject("exists").put("field", field);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exists");
        return JSON.textNode(field + ":[* TO *]");
    }

    @ApplicationScoped
    public static class Parser implements QueryPayloadParser {
        private static final Set<String> EXISTS_FIELDS = Set.of("type");

        @Override
        public String type() {
            return "exists";
        }

        @Override
        public ExistsQuery parse(JsonParser parser, ObjectMapper mapper, ObjectNode node)
                throws JsonMappingException {
            QueryNodeDeserializer.rejectUnknownFields(parser, node, EXISTS_FIELDS, "exists query");
            return new ExistsQuery();
        }
    }
}
