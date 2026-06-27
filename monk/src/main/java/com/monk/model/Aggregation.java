package com.monk.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk.json.AggregationDeserializer;
import com.monk.search.AggregationContext;
import com.monk.search.SearchEngine;
import jd.nomad.mapping.FieldType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Schema(description = "A named facet/aggregation computed per backend over root document fields",
        oneOf = {TermsAggregation.class, UniqueAggregation.class, RangeAggregation.class, SubfacetsAggregation.class,
                FilterAggregation.class, MetricAggregation.class, NestedAggregation.class})
@JsonDeserialize(using = AggregationDeserializer.class)
public sealed interface Aggregation
        permits TermsAggregation, UniqueAggregation, RangeAggregation, SubfacetsAggregation, FilterAggregation,
                MetricAggregation, NestedAggregation {
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
        return bucketsResult(SearchEngine.ELASTICSEARCH, aggregation, "key", "doc_count");
    }

    default AggregationResult parseSolr(JsonNode facet) {
        return bucketsResult(SearchEngine.SOLR, facet, "val", "count");
    }

    /**
     * Sub-aggregations run over each bucket/domain this aggregation produces. Empty for
     * single-value aggregations and for bucketing aggregations without nested {@code aggs}.
     */
    default Map<String, Aggregation> subAggregations() {
        return Map.of();
    }

    default AggregationResult bucketsResult(
            SearchEngine engine, JsonNode aggregation, String keyProperty, String countProperty) {
        List<AggregationResult.Bucket> buckets = new ArrayList<>();
        for (JsonNode bucket : aggregation.path("buckets")) {
            buckets.add(new AggregationResult.Bucket(
                    bucket.path(keyProperty),
                    bucket.path(countProperty).asLong(0),
                    parseChildren(engine, bucket)));
        }
        return AggregationResult.ofBuckets(buckets);
    }

    /**
     * Parses the {@link #subAggregations()} results nested inside a single bucket node, reading each
     * child by name (ES sub-aggregations and Solr nested facets sit as siblings of the bucket count).
     */
    default Map<String, AggregationResult> parseChildren(SearchEngine engine, JsonNode bucketNode) {
        Map<String, Aggregation> children = subAggregations();
        if (children.isEmpty()) {
            return Map.of();
        }
        Map<String, AggregationResult> results = new LinkedHashMap<>();
        children.forEach((name, child) -> results.put(name, child.parse(engine, bucketNode.path(name))));
        return Collections.unmodifiableMap(results);
    }
}
