package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record TextQuery(
        @NotBlank String type,
        @NotEmpty List<@NotBlank String> phrases
) implements QueryPayload {
    @AssertTrue(message = "type must be text")
    public boolean isTextType() {
        return "text".equals(type);
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        if (phrases.size() == 1) {
            return elasticsearchMatchPhrase(context, field, phrases.get(0));
        }

        ObjectNode root = context.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        for (String phrase : phrases) {
            should.add(elasticsearchMatchPhrase(context, field, phrase));
        }
        bool.put("minimum_should_match", 1);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        if (phrases.size() == 1) {
            return context.solrFieldQuery(field, phrases.get(0));
        }

        ObjectNode root = context.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        for (String phrase : phrases) {
            should.add(context.solrFieldQuery(field, phrase));
        }
        bool.put("mm", 1);
        return root;
    }

    private JsonNode elasticsearchMatchPhrase(QueryParseContext context, String field, String phrase) {
        ObjectNode root = context.objectNode();
        root.putObject("match_phrase").put(field, phrase);
        return root;
    }
}
