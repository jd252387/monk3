package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.mapping.SearchMappingConfig;
import com.monk3.model.QueryPayload;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualField;
import jd.nomad.mapping.VirtualMapping;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record QueryParseContext(
        SearchMapping mapping,
        DocumentMapping document,
        MappedField currentField,
        Integer minimumMatch,
        SearchMappingConfig config,
        VirtualMapping virtualMapping,
        VirtualFieldExpander expander,
        String nestedPath
) {
    public static QueryParseContext root(
            SearchMapping mapping,
            SearchMappingConfig config,
            VirtualMapping virtualMapping,
            VirtualFieldExpander expander
    ) {
        return new QueryParseContext(mapping, mapping.root(), null, null, config, virtualMapping, expander, null);
    }

    public QueryParseContext withMinimumMatch(Integer minimumMatch) {
        return copy(document, currentField, minimumMatch);
    }

    public QueryParseContext withField(MappedField mappedField) {
        return copy(document, mappedField, minimumMatch);
    }

    public QueryParseContext withNestedDocument(DocumentMapping documentMapping, String path) {
        return new QueryParseContext(mapping, documentMapping, null, minimumMatch, config, virtualMapping, expander, path);
    }

    private QueryParseContext copy(DocumentMapping document, MappedField currentField, Integer minimumMatch) {
        return new QueryParseContext(mapping, document, currentField, minimumMatch, config, virtualMapping, expander, nestedPath);
    }

    public Optional<VirtualField> findVirtualField(String logicalName) {
        if (virtualMapping == null) {
            return Optional.empty();
        }
        return virtualMapping.document(document.name()).flatMap(d -> d.field(logicalName));
    }

    public JsonNode expandVirtual(
            VirtualField virtualField,
            QueryPayload payload,
            boolean isNegated,
            SearchEngine engine
    ) {
        return expander.expandAndTranslate(virtualField, payload, isNegated, this, engine);
    }

    public int minimumMatchOrDefault(int defaultValue) {
        return minimumMatch == null ? defaultValue : minimumMatch;
    }

    public MappedField requireMappedField(String logicalName) {
        return findMappedField(logicalName)
                .orElseThrow(() -> new QueryTranslationException(
                        "Field '" + logicalName + "' is not defined in mapping document '" + document.name()
                                + "' for material type '" + mapping.materialType() + "'"));
    }

    public Optional<MappedField> findMappedField(String logicalName) {
        return document.field(logicalName);
    }

    public DocumentMapping requireDocument(String documentName) {
        return mapping.document(documentName)
                .orElseThrow(() -> new QueryTranslationException(
                        "Document type '" + documentName + "' is not defined for material type '" + mapping.materialType() + "'"));
    }

    public String requireSearchField(String queryType, FieldType... supportedTypes) {
        if (currentField == null) {
            throw new QueryTranslationException("No mapped field is available for " + queryType + " query conversion");
        }
        boolean supported = List.of(supportedTypes).contains(currentField.type());
        if (!supported) {
            throw new QueryTranslationException(
                    "Query type '" + queryType + "' is not supported for field '" + currentField.logicalName()
                            + "' with mapping type '" + currentField.type().name().toLowerCase(Locale.ROOT) + "'");
        }
        String fieldName = currentField.searchField();
        return nestedPath != null ? nestedPath + "." + fieldName : fieldName;
    }
}
