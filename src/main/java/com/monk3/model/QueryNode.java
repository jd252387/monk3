package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk3.json.QueryNodeDeserializer;
import com.monk3.search.QueryParseContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@JsonDeserialize(using = QueryNodeDeserializer.class)
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
        return field.isEmpty() ? data instanceof BooleanQueryData : data instanceof QueryPayload;
    }

    public JsonNode toElasticsearch(QueryParseContext context) {
        return data.toElasticsearch(context, this);
    }

    public JsonNode toSolr(QueryParseContext context) {
        return data.toSolr(context, this);
    }

    @JsonIgnore
    boolean isNegated() {
        return Boolean.TRUE.equals(isNot);
    }
}
