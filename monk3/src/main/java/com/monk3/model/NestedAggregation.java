package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.AggregationContext;
import com.monk3.search.AggregationContext.NestedDomain;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Runs its sub-aggregations within the domain of a subdocument (nested) field; "
        + "translates to an Elasticsearch nested aggregation and a Solr blockChildren domain change", example = """
        {
          "aggType": "nested",
          "args": { "field": "chapters" },
          "aggs": {
            "byPageCount": { "aggType": "terms", "args": { "field": "pageCount" } }
          }
        }
        """)
public record NestedAggregation(
        @NotBlank String field,
        @NotEmpty Map<String, @NotNull @Valid Aggregation> subAggregations
) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "nested";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        NestedDomain domain = context.enterNested(field, aggType(), SearchEngine.ELASTICSEARCH);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("nested").put("path", domain.path());
        root.set("aggs", domain.context().translateChildren(SearchEngine.ELASTICSEARCH, subAggregations));
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        NestedDomain domain = context.enterNested(field, aggType(), SearchEngine.SOLR);
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "query");
        // Scope the changed domain to this nest level; blockChildren alone returns all descendants.
        facet.put("q", QueryParseContext.SOLR_NEST_PATH_FIELD + ":/" + domain.nestPath());
        facet.putObject("domain").put("blockChildren", domain.blockMask());
        facet.set("facet", domain.context().translateChildren(SearchEngine.SOLR, subAggregations));
        return facet;
    }

    @Override
    public AggregationResult parseElasticsearch(JsonNode aggregation) {
        return AggregationResult.ofValue(aggregation.path("doc_count").asLong(0))
                .withAggregations(parseChildren(SearchEngine.ELASTICSEARCH, aggregation));
    }

    @Override
    public AggregationResult parseSolr(JsonNode facet) {
        return AggregationResult.ofValue(facet.path("count").asLong(0))
                .withAggregations(parseChildren(SearchEngine.SOLR, facet));
    }
}
