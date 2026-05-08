package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.mapping.DocumentMapping;
import com.monk3.mapping.MappedField;
import com.monk3.search.QueryParseContext;
import com.monk3.search.QueryTranslationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record QueryNode(
        @NotNull String field,
        @Positive Integer minimumMatch,
        Boolean isNot,
        @NotNull @Valid QueryData data
) {
    @AssertTrue(message = "field determines data shape")
    public boolean hasMatchingDataShape() {
        if (field == null || data == null) {
            return true;
        }
        return !field.isEmpty() || data instanceof BooleanQueryData;
    }

    public JsonNode toElasticsearch(QueryParseContext context) {
        JsonNode query = toElasticsearchQuery(context);
        return isNegated() ? context.elasticsearchMustNot(query) : query;
    }

    public JsonNode toSolr(QueryParseContext context) {
        JsonNode query = toSolrQuery(context);
        return isNegated() ? context.solrMustNot(query) : query;
    }

    private JsonNode toElasticsearchQuery(QueryParseContext context) {
        if (data instanceof BooleanQueryData booleanQueryData) {
            QueryParseContext booleanContext = context.withMinimumMatch(minimumMatch);
            if (field.isEmpty()) {
                return booleanQueryData.toElasticsearch(booleanContext);
            }
            NestedDocument nestedDocument = nestedDocument(context);
            return context.elasticsearchNestedQuery(
                    nestedDocument.path(),
                    booleanQueryData.toElasticsearch(booleanContext.withDocument(nestedDocument.mapping())));
        }

        if (minimumMatch != null) {
            throw new QueryTranslationException("minimumMatch is only supported on boolean query nodes");
        }
        MappedField mappedField = context.requireMappedField(field);
        if (mappedField.isSubdocument()) {
            throw new QueryTranslationException("Subdocument field '" + field + "' requires boolean query data");
        }
        return data.toElasticsearch(context.withField(mappedField));
    }

    private JsonNode toSolrQuery(QueryParseContext context) {
        if (data instanceof BooleanQueryData booleanQueryData) {
            QueryParseContext booleanContext = context.withMinimumMatch(minimumMatch);
            if (field.isEmpty()) {
                return booleanQueryData.toSolr(booleanContext);
            }
            NestedDocument nestedDocument = nestedDocument(context);
            return context.solrParentQuery(booleanQueryData.toSolr(booleanContext.withDocument(nestedDocument.mapping())));
        }

        if (minimumMatch != null) {
            throw new QueryTranslationException("minimumMatch is only supported on boolean query nodes");
        }
        MappedField mappedField = context.requireMappedField(field);
        if (mappedField.isSubdocument()) {
            throw new QueryTranslationException("Subdocument field '" + field + "' requires boolean query data");
        }
        return data.toSolr(context.withField(mappedField));
    }

    private NestedDocument nestedDocument(QueryParseContext context) {
        MappedField mappedField = context.findMappedField(field).orElse(null);
        if (mappedField != null) {
            if (!mappedField.isSubdocument()) {
                throw new QueryTranslationException("Field '" + field + "' does not map to a subdocument");
            }
            return new NestedDocument(context.requireDocument(mappedField.subdocumentType()), mappedField.searchField());
        }
        return new NestedDocument(context.requireDocument(field), field);
    }

    private boolean isNegated() {
        return Boolean.TRUE.equals(isNot);
    }

    private record NestedDocument(DocumentMapping mapping, String path) {
    }
}
