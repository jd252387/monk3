package jd.nomad.mapping;

/**
 * Configuration for a {@link FieldType#VECTOR} field. The field's {@code destinationField} carries a
 * {@code %i} placeholder that is expanded over the inclusive range {@code start..end} to yield the
 * family of physical vector fields searched together (see {@link MappedField#vectorFields()}).
 */
public record VectorSpec(int start, int end) {
}
