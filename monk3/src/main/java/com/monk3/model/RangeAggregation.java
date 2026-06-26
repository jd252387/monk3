package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.AggregationContext;
import com.monk3.search.QueryJson;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jd.nomad.mapping.FieldType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Schema(description = "Histogram of a numeric root document field, bucketed by a fixed interval between bounds", example = """
        {
          "aggType": "range",
          "args": {
            "field": "likes",
            "interval": 500,
            "from": 1000,
            "to": 10000
          }
        }
        """)
public record RangeAggregation(
        @NotBlank String field,
        @NotNull @Positive BigDecimal interval,
        @NotNull BigDecimal from,
        @NotNull BigDecimal to,
        Map<String, @NotNull @Valid Aggregation> subAggregations
) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "range";
    }

    @AssertTrue(message = "range aggregations require from to be less than to")
    public boolean hasValidBounds() {
        return from == null || to == null || from.compareTo(to) < 0;
    }

    private static final Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.NUMBER);

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SUPPORTED_FIELD_TYPES).searchField();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode histogram = root.putObject("histogram");
        histogram.put("field", searchField);
        histogram.set("interval", QueryJson.valueNode(interval));
        histogram.put("min_doc_count", 0);
        putBounds(histogram, "hard_bounds");
        putBounds(histogram, "extended_bounds");
        if (!subAggregations.isEmpty()) {
            root.set("aggs", context.translateChildren(SearchEngine.ELASTICSEARCH, subAggregations));
        }
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SUPPORTED_FIELD_TYPES).searchField();
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "range");
        facet.put("field", searchField);
        facet.set("start", QueryJson.valueNode(from));
        facet.set("end", QueryJson.valueNode(to));
        facet.set("gap", QueryJson.valueNode(interval));
        if (!subAggregations.isEmpty()) {
            facet.set("facet", context.translateChildren(SearchEngine.SOLR, subAggregations));
        }
        return facet;
    }

    private void putBounds(ObjectNode histogram, String boundsProperty) {
        ObjectNode bounds = histogram.putObject(boundsProperty);
        bounds.set("min", QueryJson.valueNode(from));
        bounds.set("max", QueryJson.valueNode(to));
    }
}
