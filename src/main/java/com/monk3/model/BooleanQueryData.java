package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.DocumentMapping;
import com.monk3.mapping.MappedField;
import com.monk3.search.QueryParseContext;
import com.monk3.search.QueryTranslationException;
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
    public JsonNode toElasticsearch(QueryParseContext context, QueryNode node) {
        QueryParseContext booleanContext = context.withMinimumMatch(node.minimumMatch());
        JsonNode query;
        if (node.field().isEmpty()) {
            query = toElasticsearch(booleanContext);
        } else {
            NestedDocument nestedDocument = nestedDocument(context, node.field());
            query = elasticsearchNestedQuery(
                    nestedDocument.path(),
                    toElasticsearch(booleanContext.withDocument(nestedDocument.mapping())));
        }
        return node.isNegated() ? elasticsearchMustNot(query) : query;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context, QueryNode node) {
        QueryParseContext booleanContext = context.withMinimumMatch(node.minimumMatch());
        JsonNode query;
        if (node.field().isEmpty()) {
            query = toSolr(booleanContext);
        } else {
            NestedDocument nestedDocument = nestedDocument(context, node.field());
            query = solrParentQuery(context, toSolr(booleanContext.withDocument(nestedDocument.mapping())));
        }
        return node.isNegated() ? solrMustNot(query) : query;
    }

    private JsonNode toElasticsearch(QueryParseContext context) {
        return toBooleanQuery(context, SearchEngine.ELASTICSEARCH, queryNode -> queryNode.toElasticsearch(context));
    }

    private JsonNode toSolr(QueryParseContext context) {
        return toBooleanQuery(context, SearchEngine.SOLR, queryNode -> queryNode.toSolr(context));
    }

    private JsonNode toBooleanQuery(
            QueryParseContext context,
            SearchEngine searchEngine,
            Function<QueryNode, JsonNode> query
    ) {
        return boolShould(searchEngine, context.minimumMatchOrDefault(1), clauses.stream()
                .map(clause -> toMustClause(clause, query))
                .toList());
    }

    private JsonNode toMustClause(
            List<QueryNode> clause,
            Function<QueryNode, JsonNode> query
    ) {
        return clause.size() == 1
                ? query.apply(clause.getFirst())
                : boolMust(clause.stream().map(query).toList());
    }

    private static JsonNode boolShould(SearchEngine searchEngine, int minimumMatch, List<JsonNode> clauses) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        clauses.forEach(should::add);
        bool.put(searchEngine.minimumShouldMatchProperty(), minimumMatch);
        return root;
    }

    private static JsonNode boolMust(List<JsonNode> clauses) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode must = root.putObject("bool").putArray("must");
        clauses.forEach(must::add);
        return root;
    }

    private static JsonNode elasticsearchMustNot(JsonNode query) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.add(JsonNodeFactory.instance.objectNode().set("match_all", JsonNodeFactory.instance.objectNode()));
        ArrayNode mustNot = bool.putArray("must_not");
        mustNot.add(query);
        return root;
    }

    private static JsonNode solrMustNot(JsonNode query) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.add("*:*");
        ArrayNode mustNot = bool.putArray("must_not");
        mustNot.add(query);
        return root;
    }

    private static JsonNode elasticsearchNestedQuery(String path, JsonNode query) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode nested = root.putObject("nested");
        nested.put("path", path);
        nested.set("query", query);
        return root;
    }

    private static JsonNode solrParentQuery(QueryParseContext context, JsonNode childQuery) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode parent = root.putObject("parent");
        parent.put("which", context.config().solr().parentBlockMask());
        parent.set("query", childQuery);
        return root;
    }

    private static NestedDocument nestedDocument(QueryParseContext context, String field) {
        MappedField mappedField = context.findMappedField(field).orElse(null);
        if (mappedField != null) {
            if (!mappedField.isSubdocument()) {
                throw new QueryTranslationException("Field '" + field + "' does not map to a subdocument");
            }
            return new NestedDocument(context.requireDocument(mappedField.subdocumentType()), mappedField.searchField());
        }
        return new NestedDocument(context.requireDocument(field), field);
    }

    private record NestedDocument(DocumentMapping mapping, String path) {
    }
}
