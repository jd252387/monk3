package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk3.json.AggregationDeserializer;
import com.monk3.search.AggregationContext;
import com.monk3.search.SearchEngine;
import jd.nomad.mapping.FieldType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Schema(description = "A named facet/aggregation computed per backend over root document fields",
        oneOf = {TermsAggregation.class, UniqueAggregation.class, RangeAggregation.class, SubfacetsAggregation.class})
@JsonDeserialize(using = AggregationDeserializer.class)
public sealed interface Aggregation
        permits TermsAggregation, UniqueAggregation, RangeAggregation, SubfacetsAggregation {
    Set<FieldType> SCALAR_FIELD_TYPES =
            Set.of(FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME, FieldType.BOOLEAN);

    JsonNode toElasticsearch(AggregationContext context);

    JsonNode toSolr(AggregationContext context);

    default JsonNode translate(SearchEngine engine, AggregationContext context) {
        return switch (engine) {
            case ELASTICSEARCH -> toElasticsearch(context);
            case SOLR -> toSolr(context);
        };
    }

    default AggregationResult parse(SearchEngine engine, JsonNode result) {
        return switch (engine) {
            case ELASTICSEARCH -> parseElasticsearch(result);
            case SOLR -> parseSolr(result);
        };
    }

    default AggregationResult parseElasticsearch(JsonNode aggregation) {
        return bucketsResult(aggregation, "key", "doc_count");
    }

    default AggregationResult parseSolr(JsonNode facet) {
        return bucketsResult(facet, "val", "count");
    }

    static AggregationResult bucketsResult(JsonNode aggregation, String keyProperty, String countProperty) {
        List<AggregationResult.Bucket> buckets = new ArrayList<>();
        for (JsonNode bucket : aggregation.path("buckets")) {
            buckets.add(new AggregationResult.Bucket(bucket.path(keyProperty), bucket.path(countProperty).asLong(0)));
        }
        return AggregationResult.ofBuckets(buckets);
    }
}
