package com.monk.model.agg;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.search.AggregationContext;
import com.monk.search.AggregationContext.NestedDomain;
import com.monk.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Runs its sub-aggregations back up the subdocument hierarchy, at the ancestor level named by "
        + "path — an ordered list of subdocument fields absolute from the root (omitted or empty returns to the "
        + "root document). Only valid inside a nested aggregation. Translates to an Elasticsearch reverse_nested "
        + "aggregation and a Solr blockParent domain change", example = """
        {
          "aggType": "reverseNested",
          "args": { "path": ["chapters"] },
          "aggs": {
            "byPageCount": { "aggType": "terms", "args": { "field": "pageCount" } }
          }
        }
        """)
public record ReverseNestedAggregation(
        @NotNull List<@NotBlank String> path,
        @NotEmpty Map<String, @NotNull @Valid Aggregation> subAggregations
) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "reverseNested";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        NestedDomain domain = context.enterReverseNested(path, aggType(), SearchEngine.ELASTICSEARCH);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode reverseNested = root.putObject("reverse_nested");
        if (domain.path() != null) {
            reverseNested.put("path", domain.path());
        }
        root.set("aggs", domain.context().translateChildren(SearchEngine.ELASTICSEARCH, subAggregations));
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        NestedDomain domain = context.enterReverseNested(path, aggType(), SearchEngine.SOLR);
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "query");
        // blockParent lands exactly on the ancestor level matching the mask; no further scoping needed.
        facet.put("q", "*:*");
        facet.putObject("domain").put("blockParent", domain.blockMask());
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
