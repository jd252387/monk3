package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.MappedField;
import com.monk3.search.QueryParseContext;
import com.monk3.search.QueryTranslationException;

public sealed interface QueryPayload extends QueryData permits TextQuery, RangeQuery, ExactQuery {
    JsonNode toElasticsearch(QueryParseContext context);

    JsonNode toSolr(QueryParseContext context);

    @Override
    default JsonNode toElasticsearch(QueryParseContext context, QueryNode node) {
        JsonNode query = toElasticsearch(context.withField(requireLeafMappedField(context, node)));
        return node.isNegated() ? elasticsearchMustNot(query) : query;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context, QueryNode node) {
        JsonNode query = toSolr(context.withField(requireLeafMappedField(context, node)));
        return node.isNegated() ? solrMustNot(query) : query;
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

    private static JsonNode elasticsearchMustNot(JsonNode query) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.add(JsonNodeFactory.instance.objectNode().set("match_all", JsonNodeFactory.instance.objectNode()));
        ArrayNode mustNot = bool.putArray("must_not");
        mustNot.add(query);
        return root;
    }

    private static JsonNode solrMustNot(JsonNode query) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        must.add("*:*");
        ArrayNode mustNot = bool.putArray("must_not");
        mustNot.add(query);
        return root;
    }
}
