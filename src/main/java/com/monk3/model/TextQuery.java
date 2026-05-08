package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
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
        return toPhraseQuery(context, SearchEngine.ELASTICSEARCH,
                phrase -> context.elasticsearchMatchPhrase(field, phrase));
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        return toPhraseQuery(context, SearchEngine.SOLR, phrase -> context.solrFieldQuery(field, phrase));
    }

    private JsonNode toPhraseQuery(
            QueryParseContext context,
            SearchEngine searchEngine,
            Function<String, JsonNode> phraseQuery
    ) {
        if (phrases.size() == 1) {
            return phraseQuery.apply(phrases.getFirst());
        }

        return context.boolShould(searchEngine, 1, phrases.stream()
                .map(phraseQuery)
                .toList());
    }
}
