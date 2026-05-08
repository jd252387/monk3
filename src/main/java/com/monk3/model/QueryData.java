package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.search.QueryParseContext;
import com.monk3.search.UnsupportedQueryDataException;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = BooleanQueryData.class)
@JsonSubTypes({
        @JsonSubTypes.Type(BooleanQueryData.class),
        @JsonSubTypes.Type(TextQuery.class),
        @JsonSubTypes.Type(RangeQuery.class)
})
public sealed interface QueryData permits BooleanQueryData, QueryPayload {
    default JsonNode toElasticsearch(QueryParseContext context) {
        throw new UnsupportedQueryDataException(getClass().getSimpleName(), "Elasticsearch");
    }

    default JsonNode toSolr(QueryParseContext context) {
        throw new UnsupportedQueryDataException(getClass().getSimpleName(), "Apache Solr");
    }
}
