package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public sealed interface ExactQuery<T> extends QueryPayload
        permits ExactQuery.Numeric, ExactQuery.Datetime, ExactQuery.BooleanValues {
    @NotEmpty
    List<@NotNull T> values();

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        return QueryJson.elasticsearchTerms(field, values());
    }

    @Override
    default JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        if (values().size() == 1) {
            return QueryJson.solrFieldQuery(field, values().getFirst());
        }

        return QueryJson.boolShould(SearchEngine.SOLR, 1, values().stream()
                .map(value -> QueryJson.solrFieldQuery(field, value))
                .map(JsonNode.class::cast)
                .toList());
    }

    record Numeric(List<BigDecimal> values) implements ExactQuery<BigDecimal> {
    }

    record Datetime(List<String> values) implements ExactQuery<String> {
    }

    record BooleanValues(List<Boolean> values) implements ExactQuery<Boolean> {
    }
}
