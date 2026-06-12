package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.monk3.search.AggregationContext;
import com.monk3.search.QueryParseContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jd.nomad.mapping.FieldType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Schema(description = "A facet with one named sub-facet per filter; each filter is a query DSL payload applied to the given root document field", example = """
        {
          "aggType": "subfacets",
          "args": {
            "field": "publishedAt",
            "filters": {
              "lastWeek": { "type": "range", "gt": "2026-02-01T00:00:00Z", "lt": "2026-02-07T00:00:00Z" },
              "lastMonth": { "type": "range", "gt": "2026-01-07T00:00:00Z", "lt": "2026-02-07T00:00:00Z" }
            }
          }
        }
        """)
public record SubfacetsAggregation(
        @NotBlank String field,
        @NotEmpty Map<String, @NotNull @Valid QueryPayload> filters
) implements Aggregation {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES =
            Set.of(FieldType.STRING, FieldType.FREETEXT, FieldType.NUMBER, FieldType.DATETIME, FieldType.BOOLEAN);

    @JsonProperty
    public String aggType() {
        return "subfacets";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        QueryParseContext payloadContext = payloadContext(context);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode namedFilters = root.putObject("filters").putObject("filters");
        filters.forEach((name, payload) -> namedFilters.set(name, payload.toElasticsearch(payloadContext)));
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        QueryParseContext payloadContext = payloadContext(context);
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "query");
        facet.put("q", "*:*");
        ObjectNode subFacets = facet.putObject("facet");
        filters.forEach((name, payload) -> {
            ObjectNode subFacet = subFacets.putObject(name);
            subFacet.put("type", "query");
            subFacet.set("q", payload.toSolr(payloadContext));
        });
        return facet;
    }

    @Override
    public AggregationResult parseElasticsearch(JsonNode aggregation) {
        JsonNode buckets = aggregation.path("buckets");
        return namedCounts(name -> buckets.path(name).path("doc_count"));
    }

    @Override
    public AggregationResult parseSolr(JsonNode facet) {
        return namedCounts(name -> facet.path(name).path("count"));
    }

    private QueryParseContext payloadContext(AggregationContext context) {
        return context.requireFacetField(field, aggType(), SUPPORTED_FIELD_TYPES).payloadContext();
    }

    private AggregationResult namedCounts(Function<String, JsonNode> countByName) {
        List<AggregationResult.Bucket> buckets = filters.keySet().stream()
                .map(name -> new AggregationResult.Bucket(TextNode.valueOf(name), countByName.apply(name).asLong(0)))
                .toList();
        return AggregationResult.ofBuckets(buckets);
    }
}
