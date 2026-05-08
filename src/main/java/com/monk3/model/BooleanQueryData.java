package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.QueryParseContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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
        ObjectNode root = context.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        for (List<QueryNode> clause : clauses) {
            should.add(toElasticsearchMustClause(context, clause));
        }
        bool.put("minimum_should_match", context.minimumMatchOrDefault(1));
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        ObjectNode root = context.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        for (List<QueryNode> clause : clauses) {
            should.add(toSolrMustClause(context, clause));
        }
        bool.put("mm", context.minimumMatchOrDefault(1));
        return root;
    }

    private JsonNode toElasticsearchMustClause(QueryParseContext context, List<QueryNode> clause) {
        if (clause.size() == 1) {
            return clause.get(0).toElasticsearch(context);
        }

        ObjectNode root = context.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        for (QueryNode queryNode : clause) {
            must.add(queryNode.toElasticsearch(context));
        }
        return root;
    }

    private JsonNode toSolrMustClause(QueryParseContext context, List<QueryNode> clause) {
        if (clause.size() == 1) {
            return clause.get(0).toSolr(context);
        }

        ObjectNode root = context.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        for (QueryNode queryNode : clause) {
            must.add(queryNode.toSolr(context));
        }
        return root;
    }
}
