package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.function.Function;

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
        return toPhraseQuery(SearchEngine.ELASTICSEARCH, phrase -> elasticsearchMatchPhrase(field, phrase));
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        return toPhraseQuery(SearchEngine.SOLR, phrase -> solrFieldQuery(field, phrase));
    }

    private JsonNode toPhraseQuery(
            SearchEngine searchEngine,
            Function<String, JsonNode> phraseQuery
    ) {
        if (phrases.size() == 1) {
            return phraseQuery.apply(phrases.getFirst());
        }

        return boolShould(searchEngine, 1, phrases.stream()
                .map(phraseQuery)
                .toList());
    }

    private static JsonNode boolShould(SearchEngine searchEngine, int minimumMatch, List<JsonNode> clauses) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        clauses.forEach(should::add);
        bool.put(searchEngine.minimumShouldMatchProperty(), minimumMatch);
        return root;
    }

    private static JsonNode elasticsearchMatchPhrase(String field, String phrase) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("match_phrase").put(field, phrase);
        return root;
    }

    private static JsonNode solrFieldQuery(String field, String value) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode fieldQuery = root.putObject("field");
        fieldQuery.put("f", field);
        fieldQuery.put("query", value);
        return root;
    }
}
