package com.monk.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.MappedField;
import com.monk.search.QueryJson;
import com.monk.search.QueryParseContext;
import com.monk.search.QueryTranslationException;
import com.monk.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = """
        A boolean combination of clauses. Each clause is a QueryNode tagged with a 'bool'
        of should (OR), must (AND), or mustNot (negation); clauses are grouped by that tag.
        """, example = """
        [
          {
            "field": "title",
            "bool": "should",
            "data": { "type": "text", "phrases": [{ "type": "phrase", "value": "history" }] }
          },
          {
            "field": "title",
            "bool": "should",
            "data": { "type": "text", "phrases": [{ "type": "phrase", "value": "science" }] }
          },
          {
            "field": "year",
            "bool": "mustNot",
            "data": { "type": "range", "gt": 1800, "lte": 1900 }
          }
        ]
        """)
public record BooleanQueryData(
        @NotEmpty List<@NotNull @Valid QueryNode> clauses
) implements QueryData {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonValue
    public List<QueryNode> jsonValue() {
        return clauses;
    }

    @Override
    public JsonNode translate(SearchEngine engine, QueryParseContext context, QueryNode node) {
        QueryParseContext booleanContext = context.withMinimumMatch(node.minimumMatch());
        if (node.field().isEmpty()) {
            return toBooleanQuery(engine, booleanContext);
        }
        NestedDocument nestedDocument = nestedDocument(context, node.field());
        ObjectNode wrapper = JSON.objectNode();
        switch (engine) {
            case ELASTICSEARCH -> {
                QueryParseContext nestedContext = booleanContext.withNestedDocument(
                        nestedDocument.mapping(), nestedDocument.path());
                wrapper.putObject("nested")
                        .put("path", nestedDocument.path())
                        .set("query", toBooleanQuery(engine, nestedContext));
            }
            case SOLR -> {
                String nestPath = context.solrChildNestPath(nestedDocument.path());
                // The block mask ('which') must match the set of parent documents at this join level:
                // the previous hierarchy's nest path, or the configured root identifier at the top level.
                String which = context.solrNestPath() == null
                        ? context.requireSolrRootBlockMask()
                        : context.solrNestPathMask();
                QueryParseContext nestedContext = booleanContext.withSolrNestedDocument(
                        nestedDocument.mapping(), nestPath);
                JsonNode scopedQuery = QueryJson.boolMust(List.of(
                        QueryJson.solrFieldQuery(QueryParseContext.SOLR_NEST_PATH_FIELD, "/" + nestPath),
                        toBooleanQuery(engine, nestedContext)));
                wrapper.putObject("parent")
                        .put("which", which)
                        .set("query", scopedQuery);
            }
        }
        return wrapper;
    }

    private JsonNode toBooleanQuery(SearchEngine engine, QueryParseContext context) {
        List<JsonNode> should = new ArrayList<>();
        List<JsonNode> must = new ArrayList<>();
        List<JsonNode> mustNot = new ArrayList<>();
        for (QueryNode clause : clauses) {
            JsonNode translated = clause.translate(engine, context);
            switch (clause.bool()) {
                case SHOULD -> should.add(translated);
                case MUST -> must.add(translated);
                case MUST_NOT -> mustNot.add(translated);
            }
        }
        return QueryJson.bool(engine, context.minimumMatchOrOne(), should, must, mustNot);
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
