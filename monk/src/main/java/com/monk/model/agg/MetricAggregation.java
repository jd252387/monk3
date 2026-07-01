package com.monk.model.agg;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.monk.search.AggregationContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Locale;

@Schema(description = "A single-value metric (sum, avg, min, or max) over a numeric root document field", example = """
        {
          "aggType": "sum",
          "args": {
            "field": "likes"
          }
        }
        """)
public record MetricAggregation(
        @NotNull Metric metric,
        @NotBlank String field
) implements Aggregation {
    /** The metric to compute; its {@link #aggType()} is the DSL name and the ES/Solr function name. */
    public enum Metric {
        SUM, AVG, MIN, MAX;

        public String aggType() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @JsonProperty
    public String aggType() {
        return metric.aggType();
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType()).searchField();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject(aggType()).put("field", searchField);
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType()).searchField();
        return TextNode.valueOf(aggType() + "(" + searchField + ")");
    }

    @Override
    public AggregationResult parseElasticsearch(JsonNode aggregation) {
        return AggregationResult.ofValue(aggregation.path("value"));
    }

    @Override
    public AggregationResult parseSolr(JsonNode facet) {
        return AggregationResult.ofValue(facet);
    }
}
