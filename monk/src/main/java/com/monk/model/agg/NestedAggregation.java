package com.monk.model.agg;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.search.AggregationContext;
import com.monk.search.AggregationContext.NestedDomain;
import com.monk.search.QueryParseContext;
import com.monk.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Runs its sub-aggregations within the domain of a subdocument (nested) field; "
        + "the path is an ordered list of subdocument fields forming the hierarchy to descend into. "
        + "Translates to an Elasticsearch nested aggregation and a Solr blockChildren domain change", example = """
        {
          "aggType": "nested",
          "args": { "path": ["chapters", "pages"] },
          "aggs": {
            "byWordCount": { "aggType": "terms", "args": { "field": "wordCount" } }
          }
        }
        """)
public record NestedAggregation(
        @NotEmpty List<@NotBlank String> path,
        @NotEmpty Map<String, @NotNull @Valid Aggregation> subAggregations
) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "nested";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        NestedDomain domain = context.enterNested(path, aggType(), SearchEngine.ELASTICSEARCH);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("nested").put("path", domain.path());
        root.set("aggs", domain.context().translateChildren(SearchEngine.ELASTICSEARCH, subAggregations));
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        NestedDomain domain = context.enterNested(path, aggType(), SearchEngine.SOLR);
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
