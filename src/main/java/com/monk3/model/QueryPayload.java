package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import jd.nomad.mapping.MappedField;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.QueryTranslationException;
import com.monk3.search.SearchEngine;

public sealed interface QueryPayload extends QueryData permits TextQuery, RangeQuery, ExactQuery {
    JsonNode toElasticsearch(QueryParseContext context);

    JsonNode toSolr(QueryParseContext context);

    @Override
    default JsonNode toElasticsearch(QueryParseContext context, QueryNode node) {
        JsonNode query = toElasticsearch(context.withField(requireLeafMappedField(context, node)));
        return node.isNegated() ? QueryJson.mustNot(SearchEngine.ELASTICSEARCH, query) : query;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context, QueryNode node) {
        JsonNode query = toSolr(context.withField(requireLeafMappedField(context, node)));
        return node.isNegated() ? QueryJson.mustNot(SearchEngine.SOLR, query) : query;
    }

    private static MappedField requireLeafMappedField(QueryParseContext context, QueryNode node) {
        if (node.minimumMatch() != null) {
            throw new QueryTranslationException("minimumMatch is only supported on boolean query nodes");
        }
        MappedField mappedField = context.requireMappedField(node.field());
        if (mappedField.isSubdocument()) {
            throw new QueryTranslationException("Subdocument field '" + node.field() + "' requires boolean query data");
        }
        return mappedField;
    }

}
