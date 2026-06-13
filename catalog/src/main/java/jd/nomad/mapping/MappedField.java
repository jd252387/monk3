package jd.nomad.mapping;

import java.util.Map;
import java.util.Optional;

public record MappedField(
        String logicalName,
        FieldType type,
        String subdocumentType,
        String destinationField,
        Map<String, SourceExpression> sourcing,
        Map<String, SourceExpression> primaryKeySourcing,
        Map<String, String> subdocumentPartialUpdate
) {
    private static final String DEFAULT_DATASOURCE = "default";
    private static final String WILDCARD_DATASOURCE = "*";

    public MappedField {
        sourcing = sourcing == null ? Map.of() : Map.copyOf(sourcing);
        primaryKeySourcing = primaryKeySourcing == null ? Map.of() : Map.copyOf(primaryKeySourcing);
        subdocumentPartialUpdate = subdocumentPartialUpdate == null ? Map.of() : Map.copyOf(subdocumentPartialUpdate);
    }

    /**
     * Compact constructor used by query-side callers (and existing tests) that carry no indexing sourcing.
     */
    public MappedField(String logicalName, FieldType type, String subdocumentType, String destinationField) {
        this(logicalName, type, subdocumentType, destinationField, Map.of(), Map.of(), Map.of());
    }

    public String searchField() {
        return destinationField == null || destinationField.isBlank() ? logicalName : destinationField;
    }

    public boolean isSubdocument() {
        return type == FieldType.SUBDOCUMENT;
    }

    /** Leaf value extraction for the given datasource, falling back to {@code default} then {@code *}. */
    public Optional<SourceExpression> sourcingFor(String datasource) {
        return resolveByDatasource(sourcing, datasource);
    }

    /** Subdocument child-id extraction for the given datasource, falling back to {@code default} then {@code *}. */
    public Optional<SourceExpression> primaryKeyFor(String datasource) {
        return resolveByDatasource(primaryKeySourcing, datasource);
    }

    /** Subdocument array operation for the given datasource, falling back to {@code default} then {@code *}. */
    public Optional<String> subdocumentPartialUpdateFor(String datasource) {
        return resolveByDatasource(subdocumentPartialUpdate, datasource);
    }

    private static <T> Optional<T> resolveByDatasource(Map<String, T> byDatasource, String datasource) {
        T value = byDatasource.get(datasource);
        if (value == null) {
            value = byDatasource.get(DEFAULT_DATASOURCE);
        }
        if (value == null) {
            value = byDatasource.get(WILDCARD_DATASOURCE);
        }
        return Optional.ofNullable(value);
    }
}
