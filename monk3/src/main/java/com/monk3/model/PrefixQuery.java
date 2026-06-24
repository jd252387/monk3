package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Schema(description = "A prefix query matching documents whose field value starts with the given prefix", example = """
        {
          "type": "prefix",
          "prefix": "mach"
        }
        """)
public record PrefixQuery(@NotBlank String prefix) implements QueryPayload {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.STRING, FieldType.FREETEXT);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    public String type() {
        return "prefix";
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("prefix", SUPPORTED_FIELD_TYPES);
        ObjectNode root = JSON.objectNode();
        root.putObject("prefix").put(field, prefix);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("prefix", SUPPORTED_FIELD_TYPES);
        ObjectNode root = JSON.objectNode();
        root.putObject("prefixanalyzed").put("f", field).put("v", prefix);
        return root;
    }
}
