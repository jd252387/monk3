package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.AggregationContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jd.nomad.mapping.FieldType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Top-N distinct values of a root document field with counts", example = """
        {
          "aggType": "terms",
          "args": {
            "field": "author",
            "size": 10
          }
        }
        """)
public record TermsAggregation(@NotBlank String field, @Positive Integer size) implements Aggregation {
    @JsonProperty
    public String aggType() {
        return "terms";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SCALAR_FIELD_TYPES).searchField();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode terms = root.putObject("terms");
        terms.put("field", searchField);
        if (size != null) {
            terms.put("size", size);
        }
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        String searchField = context.requireFacetField(field, aggType(), SCALAR_FIELD_TYPES).searchField();
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "terms");
        facet.put("field", searchField);
        if (size != null) {
            facet.put("limit", size);
        }
        return facet;
    }
}
