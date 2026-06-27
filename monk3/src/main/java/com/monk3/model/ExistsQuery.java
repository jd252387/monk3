package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Schema(description = "An exists query matching documents where the field has any value", example = """
        {
          "type": "exists"
        }
        """)
public record ExistsQuery() implements QueryPayload {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES =
            Set.of(FieldType.STRING, FieldType.FREETEXT, FieldType.NUMBER, FieldType.DATETIME, FieldType.BOOLEAN);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    public String type() {
        return "exists";
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exists", SUPPORTED_FIELD_TYPES);
        ObjectNode root = JSON.objectNode();
        root.putObject("exists").put("field", field);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exists", SUPPORTED_FIELD_TYPES);
        return JSON.textNode(field + ":[* TO *]");
    }
}
