package com.monk3.search;

import jd.nomad.mapping.MappedField;

/**
 * Picks the destination field for a query, honouring an optional morphology override.
 *
 * <p>Extracted so query types beyond {@link com.monk3.model.TextQuery} can reuse the same
 * morphology-selection rules.
 */
public final class MorphologyResolver {
    private MorphologyResolver() {
    }

    /**
     * Resolves the search field for {@code field}: its configured morphology destination when a
     * {@code morphology} is requested, otherwise the field's default {@link MappedField#searchField()}.
     *
     * @throws QueryTranslationException if a morphology is requested but not declared on the field
     */
    public static String resolveSearchField(MappedField field, String morphology) {
        if (morphology == null || morphology.isBlank()) {
            return field.searchField();
        }
        return field.morphologyField(morphology)
                .orElseThrow(() -> new QueryTranslationException(
                        "Morphology '" + morphology + "' is not configured for field '"
                                + field.logicalName() + "'"));
    }
}
