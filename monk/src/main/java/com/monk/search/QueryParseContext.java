package com.monk.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.QueryData;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualField;
import jd.nomad.mapping.VirtualMapping;

import java.util.Locale;
import java.util.Map;
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
        ObjectNode solrNamedQueries,
        Map<String, String> vapiEndpoints,
        EmbeddingClient embeddingClient
) {
    /** Key under the Solr root-level {@code queries} block holding the translated root identifier query. */
    public static final String ROOT_IDENTIFIER_KEY = "root_identifier";

    /** Solr field holding a document's nest path; used as the block-join mask and the domain scope. */
    public static final String SOLR_NEST_PATH_FIELD = "_nest_path_";

    public static QueryParseContext root(
            SearchMapping mapping,
            VirtualMapping virtualMapping,
            VirtualFieldExpander expander,
            Map<String, String> vapiEndpoints,
            EmbeddingClient embeddingClient
    ) {
        return new QueryParseContext(mapping, mapping.root(), null, null, virtualMapping, expander, null, null,
                null, JsonNodeFactory.instance.objectNode(), vapiEndpoints, embeddingClient);
    }

    public QueryParseContext withMinimumMatch(Integer minimumMatch) {
        return copy(document, currentField, minimumMatch);
    }

    public QueryParseContext withField(MappedField mappedField) {
        return copy(document, mappedField, minimumMatch);
    }

    public QueryParseContext withNestedDocument(DocumentMapping documentMapping, String path) {
        return new QueryParseContext(mapping, documentMapping, null, minimumMatch, virtualMapping, expander, path,
                solrNestPath, solrRootIdentifier, solrNamedQueries, vapiEndpoints, embeddingClient);
    }

    public QueryParseContext withSolrNestedDocument(DocumentMapping documentMapping, String solrNestPath) {
        // nestedPath stays null so Solr child leaf fields keep plain names; the nest path
        // is expressed separately via the _nest_path_ field instead of a dotted prefix.
        return new QueryParseContext(mapping, documentMapping, null, minimumMatch, virtualMapping, expander, null,
                solrNestPath, solrRootIdentifier, solrNamedQueries, vapiEndpoints, embeddingClient);
    }

    /** Stores the root identifier query already translated to engine JSON, used as the root block mask. */
    public QueryParseContext withSolrRootIdentifier(JsonNode translatedIdentifier) {
        return new QueryParseContext(mapping, document, currentField, minimumMatch, virtualMapping, expander,
                nestedPath, solrNestPath, translatedIdentifier, solrNamedQueries, vapiEndpoints, embeddingClient);
    }

    /**
     * Returns the translated root identifier query, used as the Solr {@code {!parent}} / {@code blockChildren}
     * block mask matching the root documents. Throws when the root document declares no {@code identifier}
     * (it is required as the block mask for root-level nested queries and aggregations).
     */
    public JsonNode requireSolrRootIdentifier() {
        if (solrRootIdentifier == null) {
            throw new QueryTranslationException("Root document for material type '" + mapping.materialType()
                    + "' does not declare an 'identifier', which is required as the Solr block mask for"
                    + " root-level nested queries and aggregations");
        }
        return solrRootIdentifier;
    }

    /**
     * Registers the translated root identifier under this context's Solr {@code queries} block and returns
     * the local-params reference (e.g. {@code {!v=$root_identifier}}) to use as a {@code {!parent}} block mask.
     */
    public String requireSolrRootBlockMask() {
        solrNamedQueries.set(ROOT_IDENTIFIER_KEY, requireSolrRootIdentifier());
        return "{!v=$" + ROOT_IDENTIFIER_KEY + "}";
    }

    /** Appends {@code childPath} to this context's Solr nest path (e.g. {@code chapters} → {@code chapters/pages}). */
    public String solrChildNestPath(String childPath) {
        return solrNestPath == null ? childPath : solrNestPath + "/" + childPath;
    }

    /** The block mask selecting this context's nest level (e.g. {@code _nest_path_:/chapters}). */
    public String solrNestPathMask() {
        return SOLR_NEST_PATH_FIELD + ":/" + solrNestPath;
    }

    private QueryParseContext copy(DocumentMapping document, MappedField currentField, Integer minimumMatch) {
        return new QueryParseContext(mapping, document, currentField, minimumMatch, virtualMapping, expander,
                nestedPath, solrNestPath, solrRootIdentifier, solrNamedQueries, vapiEndpoints, embeddingClient);
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

    /**
     * Returns the current field, requiring it to be a {@link FieldType#VECTOR} field. Unlike
     * {@link #requireSearchField}, a vector field expands to a family of physical fields (via
     * {@link MappedField#vectorFields()}) rather than a single search field.
     */
    public MappedField requireVectorField(String queryType) {
        if (currentField == null) {
            throw new QueryTranslationException("No mapped field is available for " + queryType + " query conversion");
        }
        if (!currentField.isVector()) {
            throw new QueryTranslationException(
                    "Query type '" + queryType + "' is not supported for field '" + currentField.logicalName()
                            + "' with mapping type '" + typeName(currentField.type()) + "'");
        }
        return currentField;
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

    /**
     * Resolves the physical facet field for {@code field}, applying the Elasticsearch nested-path prefix
     * when this context has descended into a subdocument (e.g. {@code chapters.page_count}). For Solr
     * nested contexts {@code nestedPath} stays null, so the plain field name is returned and the nest
     * path is expressed via the facet domain instead.
     */
    public String facetField(MappedField field) {
        String fieldName = field.searchField();
        return nestedPath != null ? nestedPath + "." + fieldName : fieldName;
    }

    public static String typeName(FieldType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
