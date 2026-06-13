package jd.nomad.mapping;

/**
 * Describes how a single field's value is extracted from a fetched source document for one datasource.
 *
 * <p>Exactly one of {@code jq} or {@code jsonPointer} is set: {@code jq} runs a jackson-jq program over the
 * document, {@code jsonPointer} resolves an RFC&nbsp;6901 JSON Pointer. {@code partialUpdate} names the array
 * operation to apply when the indexer runs in partial-update mode (e.g. {@code set}, {@code add}); when blank
 * the value is written with a plain {@code set}. {@code required} controls whether a missing value aborts the
 * mapping of the document.
 *
 * <p>This is the query-side catalog mirror of nomad's indexing-only {@code SourceExpression}; it adds
 * {@code jsonPointer} as an alternative to {@code jq}.
 */
public record SourceExpression(String jq, String jsonPointer, String partialUpdate, boolean required) {

    public boolean hasJq() {
        return jq != null && !jq.isBlank();
    }

    public boolean hasJsonPointer() {
        return jsonPointer != null && !jsonPointer.isBlank();
    }

    public boolean hasPartialUpdate() {
        return partialUpdate != null && !partialUpdate.isBlank();
    }
}
