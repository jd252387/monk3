package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import jd.nomad.mapping.MappedField;
import com.monk3.search.QueryParseContext;
import com.monk3.search.QueryTranslationException;
import com.monk3.search.SearchEngine;

public sealed interface QueryPayload extends QueryData permits TextQuery, RangeQuery, ExactQuery, ExistsQuery, PrefixQuery {
    JsonNode toElasticsearch(QueryParseContext context);

    JsonNode toSolr(QueryParseContext context);

    default JsonNode translate(SearchEngine engine, QueryParseContext context) {
        return switch (engine) {
            case ELASTICSEARCH -> toElasticsearch(context);
            case SOLR -> toSolr(context);
        };
    }

    @Override
    default JsonNode translate(SearchEngine engine, QueryParseContext context, QueryNode node) {
        return translate(engine, context.withField(requireLeafMappedField(context, node)));
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
