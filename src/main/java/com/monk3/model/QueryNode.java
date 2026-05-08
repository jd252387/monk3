package com.monk3.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record QueryNode(
        @NotNull String field,
        Integer minimumMatch,
        Boolean isNot,
        @NotNull @Valid QueryData data
) {
    @AssertTrue(message = "field determines data shape")
    public boolean hasMatchingDataShape() {
        if (field == null || data == null) {
            return true;
        }
        return field.isEmpty() ? data instanceof BooleanQueryData : data instanceof QueryPayload;
    }
}
