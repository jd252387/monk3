package com.monk3.search;

import com.monk3.mapping.SearchMappingConfig;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record QueryParseContext(
        SearchMapping mapping,
        DocumentMapping document,
        MappedField currentField,
        Integer minimumMatch,
        SearchMappingConfig config
) {
    public static QueryParseContext root(SearchMapping mapping, SearchMappingConfig config) {
        return new QueryParseContext(mapping, mapping.root(), null, null, config);
    }

    public QueryParseContext withMinimumMatch(Integer minimumMatch) {
        return copy(document, currentField, minimumMatch);
    }

    public QueryParseContext withField(MappedField mappedField) {
        return copy(document, mappedField, minimumMatch);
    }

    public QueryParseContext withDocument(DocumentMapping documentMapping) {
        return copy(documentMapping, null, minimumMatch);
    }

    private QueryParseContext copy(DocumentMapping document, MappedField currentField, Integer minimumMatch) {
        return new QueryParseContext(mapping, document, currentField, minimumMatch, config);
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
        return currentField.searchField();
    }
}
