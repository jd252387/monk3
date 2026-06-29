package com.monk.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.Aggregation;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record AggregationContext(
        Map<String, QueryParseContext> contextsByMaterialType,
        String aggregationName,
        ObjectNode namedQueries) {

    /**
     * Context for translating a query that references its own fields (rather than a single facet
     * field). All material types resolve to the same backend context today (see
     * {@link QueryTranslationService#translateAggregations}), so any one is representative.
     */
    public QueryParseContext queryContext() {
        return contextsByMaterialType.values().iterator().next();
    }

    /**
     * Registers a query under a Solr root-level {@code queries} block and returns the local-params
     * reference (e.g. {@code {!v=$agg_<name>}}) that a query facet's {@code q} should point at.
     */
    public String registerNamedQuery(JsonNode query) {
        String key = "agg_" + aggregationName;
        namedQueries.set(key, query);
        return "{!v=$" + key + "}";
    }

    /**
     * Translates a map of sub-aggregations into the engine's nested-aggregation object (ES {@code aggs}
     * / Solr nested {@code facet}). Child contexts share this context's backend mappings and the single
     * {@code namedQueries} block; the child's aggregation name is path-qualified ({@code parent_child})
     * so Solr named-query keys stay globally unique across nesting while the root key {@code agg_<name>}
     * is unchanged. (Names are assumed not to contain {@code _} in a way that collides; agg names are
     * short identifiers, so the readable path is preferred over an opaque counter.)
     */
    public ObjectNode translateChildren(SearchEngine engine, Map<String, Aggregation> children) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        children.forEach((childName, child) -> {
            AggregationContext childContext =
                    new AggregationContext(contextsByMaterialType, aggregationName + "_" + childName, namedQueries);
            node.set(childName, child.translate(engine, childContext));
        });
        return node;
    }

    /**
     * Resolves {@code path} — an ordered list of subdocument fields forming a hierarchy — and returns
     * the domain for a nested aggregation: a child {@link AggregationContext} whose per-material-type
     * contexts have descended into the deepest subdocument (so sub-aggregations resolve their fields
     * within it), together with the engine-specific path/mask used to build the wrapping aggregation.
     * Mirrors the subdocument resolution and nest-path/block-mask derivation in
     * {@code BooleanQueryData.translate}, walking each path segment in turn.
     */
    public NestedDomain enterNested(List<String> path, String aggType, SearchEngine engine) {
        Map<String, QueryParseContext> nestedContexts = new LinkedHashMap<>();
        Set<String> resolvedPaths = new LinkedHashSet<>();
        String elasticsearchPath = null;
        String solrBlockMask = null;
        String solrNestPath = null;
        for (Map.Entry<String, QueryParseContext> entry : contextsByMaterialType.entrySet()) {
            String materialType = entry.getKey();
            QueryParseContext current = entry.getValue();
            // The block mask must match the parent documents whose descendants this domain selects:
            // the configured root identifier at the top level, or the parent hierarchy's nest path.
            // A blockChildren domain returns all descendants and the nest-path q (built from the full
            // walked path) scopes them to the deepest level, so the mask derives once from the
            // starting context regardless of how many segments the path descends.
            if (engine == SearchEngine.SOLR) {
                solrBlockMask = current.solrNestPath() == null
                        ? solrRootBlockMask()
                        : current.solrNestPathMask();
            }
            for (String segment : path) {
                QueryParseContext segmentContext = current;
                MappedField mappedField = segmentContext.findMappedField(segment)
                        .orElseThrow(() -> missingFieldException(segmentContext, segment, materialType));
                if (!mappedField.isSubdocument()) {
                    throw new QueryTranslationException(
                            "Aggregation type '" + aggType + "' requires subdocument fields in its path; '"
                                    + segment + "' is not a subdocument field for material type '" + materialType + "'");
                }
                DocumentMapping childDocument = segmentContext.requireDocument(mappedField.subdocumentType());
                String childPath = mappedField.searchField();
                switch (engine) {
                    case ELASTICSEARCH -> {
                        String fullPath = segmentContext.nestedPath() == null
                                ? childPath
                                : segmentContext.nestedPath() + "." + childPath;
                        current = segmentContext.withNestedDocument(childDocument, fullPath);
                    }
                    case SOLR -> current = segmentContext.withSolrNestedDocument(
                            childDocument, segmentContext.solrChildNestPath(childPath));
                }
            }
            switch (engine) {
                case ELASTICSEARCH -> {
                    resolvedPaths.add(current.nestedPath());
                    elasticsearchPath = current.nestedPath();
                }
                case SOLR -> {
                    resolvedPaths.add(current.solrNestPath());
                    solrNestPath = current.solrNestPath();
                }
            }
            nestedContexts.put(materialType, current);
        }
        if (resolvedPaths.size() > 1) {
            throw new QueryTranslationException("Nested aggregation path " + path
                    + " resolves to different nested paths across the requested material types: " + resolvedPaths);
        }
        return new NestedDomain(
                new AggregationContext(nestedContexts, aggregationName, namedQueries),
                elasticsearchPath, solrBlockMask, solrNestPath);
    }

    /**
     * Returns the Solr block mask matching the root documents (the configured root {@code identifier}),
     * registering the translated identifier under the root-level {@code queries} block so a
     * {@code blockChildren} domain can reference it via local params. Throws when the root document
     * declares no identifier (it is required as the block mask for root-level nested aggregations).
     */
    public String solrRootBlockMask() {
        namedQueries.set(QueryParseContext.ROOT_IDENTIFIER_KEY, queryContext().requireSolrRootIdentifier());
        return "{!v=$" + QueryParseContext.ROOT_IDENTIFIER_KEY + "}";
    }

    public ResolvedFacetField requireFacetField(String logicalName, String aggType, Set<FieldType> supportedTypes) {
        Set<Resolution> resolutions = new LinkedHashSet<>();
        ResolvedFacetField resolved = null;
        for (Map.Entry<String, QueryParseContext> entry : contextsByMaterialType.entrySet()) {
            String materialType = entry.getKey();
            QueryParseContext context = entry.getValue();
            MappedField mappedField = context.findMappedField(logicalName)
                    .orElseThrow(() -> missingFieldException(context, logicalName, materialType));
            if (mappedField.isSubdocument()) {
                throw new QueryTranslationException(
                        "Aggregations are only supported on root document fields; '" + logicalName
                                + "' is a subdocument field for material type '" + materialType + "'");
            }
            if (!supportedTypes.contains(mappedField.type())) {
                throw new QueryTranslationException(
                        "Aggregation type '" + aggType + "' is not supported for field '" + logicalName
                                + "' with mapping type '" + QueryParseContext.typeName(mappedField.type()) + "'");
            }
            if (!mappedField.isAggregatable()) {
                throw new QueryTranslationException(
                        "Aggregation field '" + logicalName + "' is not aggregatable for material type '"
                                + materialType + "'");
            }
            resolutions.add(new Resolution(context.facetField(mappedField), mappedField.type()));
            if (resolved == null) {
                resolved = new ResolvedFacetField(context.facetField(mappedField), context.withField(mappedField));
            }
        }
        if (resolutions.size() > 1) {
            throw new QueryTranslationException("Aggregation field '" + logicalName
                    + "' resolves differently across the requested material types: " + resolutions.stream()
                    .map(Resolution::display)
                    .collect(Collectors.joining(", ", "[", "]")));
        }
        if (resolved == null) {
            throw new QueryTranslationException("No material types are available to resolve aggregation field '" + logicalName + "'");
        }
        return resolved;
    }

    private static QueryTranslationException missingFieldException(
            QueryParseContext context,
            String logicalName,
            String materialType
    ) {
        if (context.findVirtualField(logicalName).isPresent()) {
            return new QueryTranslationException("Virtual field '" + logicalName + "' cannot be used in aggregations");
        }
        return new QueryTranslationException(
                "Aggregation field '" + logicalName + "' is not defined for material type '" + materialType + "'");
    }

    private record Resolution(String searchField, FieldType type) {
        String display() {
            return searchField + " (" + QueryParseContext.typeName(type) + ")";
        }
    }

    public record ResolvedFacetField(String searchField, QueryParseContext payloadContext) {
    }

    /**
     * The domain a nested aggregation switches into: the child {@link AggregationContext} for its
     * sub-aggregations, plus the engine-specific addressing — {@code path} for the Elasticsearch
     * {@code nested} aggregation, and {@code blockMask} + {@code nestPath} for the Solr
     * {@code blockChildren} domain change (the other engine's fields are null).
     */
    public record NestedDomain(AggregationContext context, String path, String blockMask, String nestPath) {
    }
}
