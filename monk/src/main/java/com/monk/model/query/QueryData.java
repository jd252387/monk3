package com.monk.model.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk.search.QueryParseContext;
import com.monk.search.SearchEngine;

public sealed interface QueryData permits BooleanQueryData, QueryPayload {
    JsonNode translate(SearchEngine engine, QueryParseContext context, QueryNode node);
}
