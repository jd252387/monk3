package com.monk.model;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "One sort key: a logical field name (or the reserved '_score' token for relevance) and a direction")
public record SortClause(
        @NotBlank @Schema(description = "Logical field to sort by, or '_score' for relevance", example = "year")
        String field,
        @Schema(description = "Sort direction; defaults to asc", example = "desc")
        SortOrder order
) {
    /** The reserved field token that sorts by relevance score instead of a mapped field. */
    public static final String SCORE_TOKEN = "_score";

    public SortClause {
        order = order == null ? SortOrder.ASC : order;
    }

    public boolean isScore() {
        return SCORE_TOKEN.equals(field);
    }
}
