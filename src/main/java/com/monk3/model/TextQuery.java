package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

@Schema(description = "A free-text / phrase search query", example = """
        {
          "type": "text",
          "phrases": ["machine learning"]
        }
        """)
public record TextQuery(
        @NotBlank String type,
        @NotEmpty List<@NotBlank String> phrases
) implements QueryPayload {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        return toPhraseQuery(SearchEngine.ELASTICSEARCH, phrase -> {
            ObjectNode root = JSON.objectNode();
            root.putObject("match_phrase").put(field, phrase);
            return root;
        });
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        return toPhraseQuery(SearchEngine.SOLR, phrase -> {
            ObjectNode root = JSON.objectNode();
            ObjectNode fieldQuery = root.putObject("field");
            fieldQuery.put("f", field).set("query", QueryJson.valueNode(phrase));
            return root;
        });
    }

    private JsonNode toPhraseQuery(
            SearchEngine searchEngine,
            Function<String, JsonNode> phraseQuery
    ) {
        if (phrases.size() == 1) {
            return phraseQuery.apply(phrases.getFirst());
        }

        return QueryJson.boolShould(searchEngine, 1, phrases.stream()
                .map(phraseQuery)
                .toList());
    }
}
