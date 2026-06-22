package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Normalized aggregation result: bucketed counts for terms, range, and subfacets aggregations, or a single value for unique aggregations", example = """
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
        @Schema(description = "Single value, present for unique aggregations") Long value
) {
    public static AggregationResult ofBuckets(List<Bucket> buckets) {
        return new AggregationResult(List.copyOf(buckets), null);
    }

    public static AggregationResult ofValue(long value) {
        return new AggregationResult(null, value);
    }

    @Schema(description = "A single aggregation bucket")
    public record Bucket(
            @Schema(description = "Bucket key: a term value, a range bucket start, or a sub-facet name") JsonNode key,
            @Schema(description = "Number of matching documents") long count
    ) {
    }
}
