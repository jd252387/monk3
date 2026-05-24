package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.search.QueryParseContext;

public sealed interface QueryData permits BooleanQueryData, QueryPayload {
    JsonNode toElasticsearch(QueryParseContext context, QueryNode node);

    JsonNode toSolr(QueryParseContext context, QueryNode node);
}
