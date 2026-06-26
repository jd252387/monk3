package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.monk3.search.AggregationContext;
import jakarta.validation.constraints.NotBlank;
import jd.nomad.mapping.FieldType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Schema(description = "Maximum of a numeric root document field", example = """
        {
          "aggType": "max",
          "args": {
            "field": "likes"
          }
        }
        """)
public record MaxAggregation(@NotBlank String field) implements Aggregation {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.NUMBER);

    @JsonProperty
    public String aggType() {
        return "max";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SUPPORTED_FIELD_TYPES).searchField();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putObject(aggType()).put("field", searchField);
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SUPPORTED_FIELD_TYPES).searchField();
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
