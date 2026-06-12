package com.monk3.search;

import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record AggregationContext(Map<String, QueryParseContext> contextsByMaterialType) {

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
            resolutions.add(new Resolution(mappedField.searchField(), mappedField.type()));
            if (resolved == null) {
                resolved = new ResolvedFacetField(mappedField.searchField(), context.withField(mappedField));
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
}
