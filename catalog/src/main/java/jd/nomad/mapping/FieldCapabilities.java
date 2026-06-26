package jd.nomad.mapping;

/**
 * Per-field capability flags declared in the physical mapping, controlling which query operations a
 * field may participate in. By default a field can be searched on and have its stored value fetched,
 * but cannot be aggregated on or sorted by. A query that uses a field for an operation it does not
 * permit is rejected.
 *
 * <p>{@code sortable} is parsed and retained for completeness; the query DSL does not yet expose a
 * sort surface, so the flag has no enforcement point today.
 */
public record FieldCapabilities(
        boolean searchable,
        boolean fetchable,
        boolean aggregatable,
        boolean sortable
) {
    private static final FieldCapabilities DEFAULTS = new FieldCapabilities(true, true, false, false);

    /** Default capabilities: searchable and fetchable, neither aggregatable nor sortable. */
    public static FieldCapabilities defaults() {
        return DEFAULTS;
    }
}
