package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.monk3.search.AggregationContext;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.VirtualField;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        @NotEmpty Map<String, @NotNull @Valid QueryPayload> filters,
        Map<String, @NotNull @Valid Aggregation> subAggregations
) implements Aggregation {
    private static final Set<FieldType> SUPPORTED_FIELD_TYPES =
            Set.of(FieldType.STRING, FieldType.FREETEXT, FieldType.NUMBER, FieldType.DATETIME, FieldType.BOOLEAN);

    @JsonProperty
    public String aggType() {
        return "subfacets";
    }

    @Override
    public JsonNode toElasticsearch(AggregationContext context) {
        Function<QueryPayload, JsonNode> translate = filterTranslator(context, SearchEngine.ELASTICSEARCH);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode namedFilters = root.putObject("filters").putObject("filters");
        filters.forEach((name, payload) -> namedFilters.set(name, translate.apply(payload)));
        if (!subAggregations.isEmpty()) {
            root.set("aggs", context.translateChildren(SearchEngine.ELASTICSEARCH, subAggregations));
        }
        return root;
    }

    @Override
    public JsonNode toSolr(AggregationContext context) {
        Function<QueryPayload, JsonNode> translate = filterTranslator(context, SearchEngine.SOLR);
        ObjectNode facet = JsonNodeFactory.instance.objectNode();
        facet.put("type", "query");
        facet.put("q", "*:*");
        ObjectNode subFacets = facet.putObject("facet");
        filters.forEach((name, payload) -> {
            ObjectNode subFacet = subFacets.putObject(name);
            subFacet.put("type", "query");
            subFacet.set("q", translate.apply(payload));
            // Each filter is its own bucket/domain; sub-aggregations nest inside each filter facet
            // (not the *:* parent) so they match Elasticsearch's per-filter-bucket semantics.
            if (!subAggregations.isEmpty()) {
                subFacet.set("facet", context.translateChildren(SearchEngine.SOLR, subAggregations));
            }
        });
        return facet;
    }

    @Override
    public AggregationResult parseElasticsearch(JsonNode aggregation) {
        JsonNode buckets = aggregation.path("buckets");
        return namedCounts(SearchEngine.ELASTICSEARCH, "doc_count", buckets::path);
    }

    @Override
    public AggregationResult parseSolr(JsonNode facet) {
        return namedCounts(SearchEngine.SOLR, "count", facet::path);
    }

    /**
     * Builds the per-filter translator for {@code field}. When {@code field} is a virtual field, each
     * filter payload is expanded through the virtual template (as the {@code {{data}}} substitution),
     * exactly as a normal leaf query would be; the {@code VirtualFieldExpander} enforces payload/type
     * compatibility. Otherwise the field resolves to a physical facet field and each payload is
     * translated against it.
     */
    private Function<QueryPayload, JsonNode> filterTranslator(AggregationContext context, SearchEngine engine) {
        QueryParseContext queryContext = context.queryContext();
        Optional<VirtualField> virtualField = queryContext.findVirtualField(field);
        if (virtualField.isPresent()) {
            VirtualField vf = virtualField.get();
            return payload -> queryContext.expandVirtual(vf, payload, engine);
        }
        QueryParseContext payloadContext =
                context.requireFacetField(field, aggType(), SUPPORTED_FIELD_TYPES).payloadContext();
        return payload -> payload.translate(engine, payloadContext);
    }

    private AggregationResult namedCounts(
            SearchEngine engine, String countProperty, Function<String, JsonNode> bucketByName) {
        List<AggregationResult.Bucket> buckets = filters.keySet().stream()
                .map(name -> {
                    JsonNode bucketNode = bucketByName.apply(name);
                    return new AggregationResult.Bucket(
                            TextNode.valueOf(name),
                            bucketNode.path(countProperty).asLong(0),
                            parseChildren(engine, bucketNode));
                })
                .toList();
        return AggregationResult.ofBuckets(buckets);
    }
}
