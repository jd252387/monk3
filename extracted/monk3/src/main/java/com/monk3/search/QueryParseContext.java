package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.model.QueryData;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualField;
import jd.nomad.mapping.VirtualMapping;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public record QueryParseContext(
        SearchMapping mapping,
        DocumentMapping document,
        MappedField currentField,
        Integer minimumMatch,
        VirtualMapping virtualMapping,
        VirtualFieldExpander expander,
        String nestedPath,
        String solrNestPath,
        JsonNode solrRootIdentifier,
        ObjectNode solrNamedQueries
) {
    /** Key under the Solr root-level {@code queries} block holding the translated root identifier query. */
    private static final String ROOT_IDENTIFIER_KEY = "root_identifier";

    public static QueryParseContext root(
            SearchMapping mapping,
            VirtualMapping virtualMapping,
            VirtualFieldExpander expander
    ) {
        return new QueryParseContext(mapping, mapping.root(), null, null, virtualMapping, expander, null, null,
                null, JsonNodeFactory.instance.objectNode());
    }

    public QueryParseContext withMinimumMatch(Integer minimumMatch) {
        return copy(document, currentField, minimumMatch);
    }

    public QueryParseContext withField(MappedField mappedField) {
        return copy(document, mappedField, minimumMatch);
    }

    public QueryParseContext withNestedDocument(DocumentMapping documentMapping, String path) {
        return new QueryParseContext(mapping, documentMapping, null, minimumMatch, virtualMapping, expander, path,
                solrNestPath, solrRootIdentifier, solrNamedQueries);
    }

    public QueryParseContext withSolrNestedDocument(DocumentMapping documentMapping, String solrNestPath) {
        // nestedPath stays null so Solr child leaf fields keep plain names; the nest path
        // is expressed separately via the _nest_path_ field instead of a dotted prefix.
        return new QueryParseContext(mapping, documentMapping, null, minimumMatch, virtualMapping, expander, null,
                solrNestPath, solrRootIdentifier, solrNamedQueries);
    }

    /** Stores the root identifier query already translated to engine JSON, used as the root block mask. */
    public QueryParseContext withSolrRootIdentifier(JsonNode translatedIdentifier) {
        return new QueryParseContext(mapping, document, currentField, minimumMatch, virtualMapping, expander,
                nestedPath, solrNestPath, translatedIdentifier, solrNamedQueries);
    }

    /**
     * Registers the translated root identifier under the Solr {@code queries} block and returns the
     * local-params reference (e.g. {@code {!v=$root_identifier}}) to use as a {@code {!parent}} block mask.
     */
    public String registerSolrRootBlockMask() {
        solrNamedQueries.set(ROOT_IDENTIFIER_KEY, solrRootIdentifier);
        return "{!v=$" + ROOT_IDENTIFIER_KEY + "}";
    }

    private QueryParseContext copy(DocumentMapping document, MappedField currentField, Integer minimumMatch) {
        return new QueryParseContext(mapping, document, currentField, minimumMatch, virtualMapping, expander,
                nestedPath, solrNestPath, solrRootIdentifier, solrNamedQueries);
    }

    public Optional<VirtualField> findVirtualField(String logicalName) {
        if (virtualMapping == null) {
            return Optional.empty();
        }
        return virtualMapping.document(document.name()).flatMap(d -> d.field(logicalName));
    }

    public JsonNode expandVirtual(VirtualField virtualField, QueryData data, SearchEngine engine) {
        return expander.expandAndTranslate(virtualField, data, this, engine);
    }

    public int minimumMatchOrOne() {
        return minimumMatch == null ? 1 : minimumMatch;
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

    public String requireSearchField(String queryType, Set<FieldType> supportedTypes) {
        return requireSearchField(queryType, supportedTypes, null);
    }

    public String requireSearchField(String queryType, Set<FieldType> supportedTypes, String morphology) {
        if (currentField == null) {
            throw new QueryTranslationException("No mapped field is available for " + queryType + " query conversion");
        }
        if (!supportedTypes.contains(currentField.type())) {
            throw new QueryTranslationException(
                    "Query type '" + queryType + "' is not supported for field '" + currentField.logicalName()
                            + "' with mapping type '" + typeName(currentField.type()) + "'");
        }
        String fieldName = MorphologyResolver.resolveSearchField(currentField, morphology);
        return nestedPath != null ? nestedPath + "." + fieldName : fieldName;
    }

    public static String typeName(FieldType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
