package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.function.Function;

public record BooleanQueryData(
        @NotEmpty List<@NotEmpty List<@NotNull @Valid QueryNode>> clauses
) implements QueryData {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public BooleanQueryData {
    }

    @JsonValue
    public List<List<QueryNode>> jsonValue() {
        return clauses;
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        return toBooleanQuery(context, SearchEngine.ELASTICSEARCH, queryNode -> queryNode.toElasticsearch(context));
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        return toBooleanQuery(context, SearchEngine.SOLR, queryNode -> queryNode.toSolr(context));
    }

    private JsonNode toBooleanQuery(
            QueryParseContext context,
            SearchEngine searchEngine,
            Function<QueryNode, JsonNode> query
    ) {
        return context.boolShould(searchEngine, context.minimumMatchOrDefault(1), clauses.stream()
                .map(clause -> toMustClause(context, clause, query))
                .toList());
    }

    private JsonNode toMustClause(
            QueryParseContext context,
            List<QueryNode> clause,
            Function<QueryNode, JsonNode> query
    ) {
        return clause.size() == 1
                ? query.apply(clause.getFirst())
                : context.boolMust(clause.stream().map(query).toList());
    }
}
