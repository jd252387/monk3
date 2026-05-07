package com.monk3.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public record RangeQuery(
        @NotBlank String type,
        RangeBound gte,
        RangeBound gt,
        RangeBound lte,
        RangeBound lt
) implements QueryPayload {
    @AssertTrue(message = "type must be range")
    public boolean isRangeType() {
        return "range".equals(type);
    }

    @AssertTrue(message = "at least one range bound is required")
    public boolean hasAtLeastOneBound() {
        return gte != null || gt != null || lte != null || lt != null;
    }

    @AssertTrue(message = "gte and gt cannot both be provided")
    public boolean hasSingleLowerBound() {
        return gte == null || gt == null;
    }

    @AssertTrue(message = "lte and lt cannot both be provided")
    public boolean hasSingleUpperBound() {
        return lte == null || lt == null;
    }
}
