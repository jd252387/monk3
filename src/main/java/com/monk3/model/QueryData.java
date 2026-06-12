package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;

public sealed interface QueryData permits BooleanQueryData, QueryPayload {
    JsonNode translate(SearchEngine engine, QueryParseContext context, QueryNode node);
}
