package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.function.Function;

public record TextQuery(
        @NotBlank String type,
        @NotEmpty List<@NotBlank String> phrases
) implements QueryPayload {
    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        return toPhraseQuery(SearchEngine.ELASTICSEARCH, phrase -> QueryJson.elasticsearchMatchPhrase(field, phrase));
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("text", FieldType.STRING, FieldType.FREETEXT);
        return toPhraseQuery(SearchEngine.SOLR, phrase -> QueryJson.solrFieldQuery(field, phrase));
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
