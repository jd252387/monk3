package com.monk3.model;

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

    @JsonValue
    public List<List<QueryNode>> jsonValue() {
        return clauses;
    }

    @Override
    public JsonNode translate(SearchEngine engine, QueryParseContext context, QueryNode node) {
        QueryParseContext booleanContext = context.withMinimumMatch(node.minimumMatch());
        if (node.field().isEmpty()) {
            return toBooleanQuery(engine, booleanContext);
        }
        NestedDocument nestedDocument = nestedDocument(context, node.field());
        QueryParseContext nestedContext = booleanContext.withNestedDocument(nestedDocument.mapping(), nestedDocument.path());
        ObjectNode wrapper = JSON.objectNode();
        switch (engine) {
            case ELASTICSEARCH -> wrapper.putObject("nested")
                    .put("path", nestedDocument.path())
                    .set("query", toBooleanQuery(engine, nestedContext));
            case SOLR -> wrapper.putObject("parent")
                    .put("which", nestedDocument.mapping().blockMask().orElseThrow(() ->
                            new QueryTranslationException("Subdocument '" + nestedDocument.mapping().name()
                                    + "' does not declare a 'block_mask', which is required for Solr nested queries")))
                    .set("query", toBooleanQuery(engine, nestedContext));
        }
        return wrapper;
    }

    private JsonNode toBooleanQuery(SearchEngine engine, QueryParseContext context) {
        return QueryJson.boolShould(engine, context.minimumMatchOrOne(), clauses.stream()
                .map(clause -> toMustClause(engine, context, clause))
                .toList());
    }

    private static JsonNode toMustClause(SearchEngine engine, QueryParseContext context, List<QueryNode> clause) {
        return clause.size() == 1
                ? clause.getFirst().translate(engine, context)
                : QueryJson.boolMust(clause.stream().map(node -> node.translate(engine, context)).toList());
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
