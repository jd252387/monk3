package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk3.json.AggregationDeserializer;
import com.monk3.search.AggregationContext;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "A named facet/aggregation computed per backend over root document fields",
        oneOf = {TermsAggregation.class, UniqueAggregation.class, RangeAggregation.class, SubfacetsAggregation.class})
@JsonDeserialize(using = AggregationDeserializer.class)
public sealed interface Aggregation
        permits TermsAggregation, UniqueAggregation, RangeAggregation, SubfacetsAggregation {
    String field();

    JsonNode toElasticsearch(AggregationContext context);

    JsonNode toSolr(AggregationContext context);

    AggregationResult parseElasticsearch(JsonNode aggregation);

    AggregationResult parseSolr(JsonNode facet);

    static AggregationResult bucketsResult(JsonNode aggregation, String keyProperty, String countProperty) {
        List<AggregationResult.Bucket> buckets = new ArrayList<>();
        for (JsonNode bucket : aggregation.path("buckets")) {
            buckets.add(new AggregationResult.Bucket(bucket.path(keyProperty), bucket.path(countProperty).asLong(0)));
        }
        return AggregationResult.ofBuckets(buckets);
    }
}
