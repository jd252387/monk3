package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.AggregationContext;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "A single-bucket facet counting documents that match a query; the query is a list of "
        + "query DSL nodes combined with a must (AND) relationship", example = """
        {
          "aggType": "filter",
          "args": {
            "query": [
              { "field": "numValue", "data": { "type": "range", "gt": 0.6, "lt": 1.0 } },
              { "field": "termValue", "data": { "type": "text", "phrases": [{ "type": "phrase", "value": "machine learning" }] } }
            ]
          }
        }
        """)
public record FilterAggregation(
        @NotEmpty List<@NotNull @Valid QueryNode> query
) implements Aggregation {

    @JsonProperty
    public String aggType() {
        return "filter";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        JsonNode filterQuery = translateMust(SearchEngine.ELASTICSEARCH, context.queryContext());
        return JsonNodeFactory.instance.objectNode().set("filter", filterQuery);
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        JsonNode filterQuery = translateMust(SearchEngine.SOLR, context.queryContext());
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "query");
        facet.put("q", context.registerNamedQuery(filterQuery));
        return facet;
    }

    @Override
    public AggregationResult parseElasticsearch(JsonNode aggregation) {
        return AggregationResult.ofValue(aggregation.path("doc_count").asLong(0));
    }

    @Override
    public AggregationResult parseSolr(JsonNode facet) {
        return AggregationResult.ofValue(facet.path("count").asLong(0));
    }

    // A single node is used as-is; multiple nodes combine with a must (AND) relationship.
    private JsonNode translateMust(SearchEngine engine, QueryParseContext context) {
        return query.size() == 1
                ? query.getFirst().translate(engine, context)
                : QueryJson.boolMust(query.stream().map(node -> node.translate(engine, context)).toList());
    }
}
