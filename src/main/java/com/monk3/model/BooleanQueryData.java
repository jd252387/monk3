package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.MappedField;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.QueryTranslationException;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

@Schema(description = """
        A boolean combination of clauses. The outer list is OR'd (should clauses);
        each inner list is AND'd (must clauses).
        """, example = """
        [
          [
            {
              "field": "title",
              "data": { "type": "text", "phrases": ["history"] }
            },
            {
              "field": "year",
              "isNot": true,
              "data": { "type": "range", "gt": 1800, "lte": 1900 }
            }
          ],
          [
            {
              "field": "title",
              "data": { "type": "text", "phrases": ["science"] }
            }
          ]
        ]
        """)
public record BooleanQueryData(
        @NotEmpty List<@NotEmpty List<@NotNull @Valid QueryNode>> clauses
) implements QueryData {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

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
            ObjectNode nested = JSON.objectNode();
            nested.putObject("nested")
                    .put("path", nestedDocument.path())
                    .set("query", toElasticsearch(booleanContext.withNestedDocument(nestedDocument.mapping(), nestedDocument.path())));
            query = nested;
        }
        return node.isNegated() ? QueryJson.mustNot(SearchEngine.ELASTICSEARCH, query) : query;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context, QueryNode node) {
        QueryParseContext booleanContext = context.withMinimumMatch(node.minimumMatch());
        JsonNode query;
        if (node.field().isEmpty()) {
            query = toSolr(booleanContext);
        } else {
            NestedDocument nestedDocument = nestedDocument(context, node.field());
            ObjectNode parent = JSON.objectNode();
            parent.putObject("parent")
                    .put("which", nestedDocument.mapping().blockMask().orElseThrow(() ->
                            new QueryTranslationException("Subdocument '" + nestedDocument.mapping().name()
                                    + "' does not declare a 'block_mask', which is required for Solr nested queries")))
                    .set("query", toSolr(booleanContext.withNestedDocument(nestedDocument.mapping(), nestedDocument.path())));
            query = parent;
        }
        return node.isNegated() ? QueryJson.mustNot(SearchEngine.SOLR, query) : query;
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
        return QueryJson.boolShould(searchEngine, context.minimumMatchOrDefault(1), clauses.stream()
                .map(clause -> toMustClause(clause, query))
                .toList());
    }

    private JsonNode toMustClause(
            List<QueryNode> clause,
            Function<QueryNode, JsonNode> query
    ) {
        return clause.size() == 1
                ? query.apply(clause.getFirst())
                : QueryJson.boolMust(clause.stream().map(query).toList());
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
