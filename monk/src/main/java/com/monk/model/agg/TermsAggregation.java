package com.monk.model.agg;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.search.AggregationContext;
import com.monk.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Top-N distinct values of a root document field with counts", example = """
        {
          "aggType": "terms",
          "args": {
            "field": "author",
            "size": 10
          }
        }
        """)
public record TermsAggregation(
        @NotBlank String field,
        @Positive Integer size,
        Map<String, @NotNull @Valid Aggregation> subAggregations
) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "terms";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType()).searchField();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode terms = root.putObject("terms");
        terms.put("field", searchField);
        if (size != null) {
            terms.put("size", size);
        }
        if (!subAggregations.isEmpty()) {
            root.set("aggs", context.translateChildren(SearchEngine.ELASTICSEARCH, subAggregations));
        }
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType()).searchField();
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "terms");
        facet.put("field", searchField);
        if (size != null) {
            facet.put("limit", size);
        }
        if (!subAggregations.isEmpty()) {
            facet.set("facet", context.translateChildren(SearchEngine.SOLR, subAggregations));
        }
        return facet;
    }
}
