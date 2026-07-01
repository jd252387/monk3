package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.search.QueryParseContext;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
}
