package jd.nomad.mapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

public record MappedField(
        String logicalName,
        FieldType type,
        String subdocumentType,
        String destinationField,
        Map<String, SourceExpression> sourcing,
        Map<String, SourceExpression> primaryKeySourcing,
        Map<String, String> subdocumentPartialUpdate,
        Map<String, String> morphologies,
        VectorSpec vectorSpec
) {
    private static final String DEFAULT_DATASOURCE = "default";
    private static final String WILDCARD_DATASOURCE = "*";
    /** Placeholder in a {@link FieldType#VECTOR} {@code destinationField} replaced by each field index. */
    public static final String VECTOR_INDEX_PLACEHOLDER = "%i";

    public MappedField {
        sourcing = sourcing == null ? Map.of() : Map.copyOf(sourcing);
        primaryKeySourcing = primaryKeySourcing == null ? Map.of() : Map.copyOf(primaryKeySourcing);
        subdocumentPartialUpdate = subdocumentPartialUpdate == null ? Map.of() : Map.copyOf(subdocumentPartialUpdate);
        morphologies = morphologies == null ? Map.of() : Map.copyOf(morphologies);
    }

    /**
     * Compact constructor used by query-side callers (and existing tests) that carry no indexing sourcing.
     */
    public MappedField(String logicalName, FieldType type, String subdocumentType, String destinationField) {
        this(logicalName, type, subdocumentType, destinationField, Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    public String searchField() {
        return destinationField == null || destinationField.isBlank() ? logicalName : destinationField;
    }

    /** Destination field for the given morphology (e.g. {@code english}), if one is configured. */
    public Optional<String> morphologyField(String morphology) {
        return Optional.ofNullable(morphologies.get(morphology));
    }

    public boolean isSubdocument() {
        return type == FieldType.SUBDOCUMENT;
    }

    public boolean isVector() {
        return type == FieldType.VECTOR;
    }

    /**
     * Expands the {@code %i} placeholder in {@link #searchField()} over the inclusive range declared by
     * {@link #vectorSpec()} (e.g. {@code vector_%i}, 1..3 → {@code vector_1, vector_2, vector_3}).
     */
    public List<String> vectorFields() {
        if (vectorSpec == null) {
            throw new IllegalStateException("Field '" + logicalName + "' is not a vector field");
        }
        String template = searchField();
        return IntStream.rangeClosed(vectorSpec.start(), vectorSpec.end())
                .mapToObj(index -> template.replace(VECTOR_INDEX_PLACEHOLDER, Integer.toString(index)))
                .toList();
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
