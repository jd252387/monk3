package com.monk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Normalized aggregation result: bucketed counts for terms, range, and subfacets aggregations, or a single value for unique, filter, and metric (sum/avg/min/max) aggregations", example = """
        {
          "buckets": [
            { "key": "Jane Doe", "count": 8 },
            { "key": "John Smith", "count": 3 }
          ]
        }
        """)
public record AggregationResult(
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Bucketed counts, present for terms, range, and subfacets aggregations") List<Bucket> buckets,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Single value, present for unique, filter, and metric (sum/avg/min/max) aggregations; integer counts stay integers, while metrics may be fractional") JsonNode value,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "Nested sub-aggregation results keyed by sub-aggregation name, present when a single-bucket filter aggregation declares sub-aggregations") Map<String, AggregationResult> aggregations
) {
    public static AggregationResult ofBuckets(List<Bucket> buckets) {
        return new AggregationResult(List.copyOf(buckets), null, Map.of());
    }

    public static AggregationResult ofValue(long value) {
        return new AggregationResult(null, JsonNodeFactory.instance.numberNode(value), Map.of());
    }

    public static AggregationResult ofValue(JsonNode value) {
        return new AggregationResult(null, value, Map.of());
    }

    public AggregationResult withAggregations(Map<String, AggregationResult> aggregations) {
        return new AggregationResult(buckets, value, aggregations);
    }

    @Schema(description = "A single aggregation bucket")
    public record Bucket(
            @Schema(description = "Bucket key: a term value, a range bucket start, or a sub-facet name") JsonNode key,
            @Schema(description = "Number of matching documents") long count,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @Schema(description = "Per-bucket sub-aggregation results keyed by sub-aggregation name") Map<String, AggregationResult> aggregations
    ) {
    }
}
