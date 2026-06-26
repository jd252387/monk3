package jd.nomad.mapping;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import jd.nomad.config.IndexingConfig;
import jd.nomad.config.catalog.ConfigurationCatalogService;

/**
 * Collects the set of top-level source field names referenced by the active material type's mapping for the
 * configured datasource. Datasources that support selective field retrieval (MongoDB projections, REST field
 * parameters) use this to avoid fetching the whole document.
 *
 * <p>For {@code jq} sourcing the root field is parsed from the expression via {@link JqSourceFieldExtractor};
 * for {@code jsonPointer} sourcing it is the pointer's first (unescaped) segment.
 */
@ApplicationScoped
public class MappingFieldCollector {

    private final ConfigurationCatalogService catalogService;
    private final IndexingConfig indexerConfig;
    private final JqSourceFieldExtractor fieldExtractor;

    @Inject
    public MappingFieldCollector(ConfigurationCatalogService catalogService, IndexingConfig indexerConfig) {
        this.catalogService = catalogService;
        this.indexerConfig = indexerConfig;
        this.fieldExtractor = new JqSourceFieldExtractor();
    }

    public Set<String> collectFields() {
        SearchMapping mapping = catalogService.mappingForMaterialType(indexerConfig.materialType());
        String datasource = indexerConfig.dataSource();
        LinkedHashSet<String> sourceFields = new LinkedHashSet<>();
        collectFromDocument(mapping, mapping.root(), datasource, sourceFields);
        return sourceFields;
    }

    private void collectFromDocument(
            SearchMapping mapping, DocumentMapping document, String datasource, Set<String> accumulator) {
        for (MappedField field : document.fields().values()) {
            if (field.isSubdocument()) {
                field.primaryKeyFor(datasource)
                        .flatMap(expression -> sourceField(expression))
                        .ifPresent(accumulator::add);
                mapping.document(field.subdocumentType())
                        .ifPresent(child -> collectFromDocument(mapping, child, datasource, accumulator));
                continue;
            }
            field.sourcingFor(datasource).flatMap(expression -> sourceField(expression)).ifPresent(accumulator::add);
        }
    }

    private Optional<String> sourceField(SourceExpression expression) {
        if (expression.hasJsonPointer()) {
            return topLevelPointerSegment(expression.jsonPointer());
        }
        return fieldExtractor.extractSourceField(expression.jq());
    }

    /** Returns the first (unescaped) segment of an RFC 6901 JSON Pointer, e.g. {@code /subItems/0/name -> subItems}. */
    private Optional<String> topLevelPointerSegment(String jsonPointer) {
        if (jsonPointer == null || jsonPointer.isBlank()) {
            return Optional.empty();
        }
        String[] segments = jsonPointer.split("/");
        for (String segment : segments) {
            if (!segment.isBlank()) {
                return Optional.of(segment.replace("~1", "/").replace("~0", "~"));
            }
        }
        return Optional.empty();
    }
}
