package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.monk.search.AggregationContext;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Count of distinct values of a root document field", example = """
        {
          "aggType": "unique",
          "args": {
            "field": "involved_people"
          }
        }
        """)
public record UniqueAggregation(@NotBlank String field) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "unique";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SCALAR_FIELD_TYPES).searchField();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject("cardinality").put("field", searchField);
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SCALAR_FIELD_TYPES).searchField();
        return TextNode.valueOf("unique(" + searchField + ")");
    }

    @Override
    public AggregationResult parseElasticsearch(JsonNode aggregation) {
        return AggregationResult.ofValue(aggregation.path("value").asLong(0));
    }

    @Override
    public AggregationResult parseSolr(JsonNode facet) {
        return AggregationResult.ofValue(facet.asLong(0));
    }
}
