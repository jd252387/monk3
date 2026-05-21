package com.monk3.model;

import java.math.BigDecimal;

public record NumericRangeQuery(
        BigDecimal gte,
        BigDecimal gt,
        BigDecimal lte,
        BigDecimal lt
) implements RangeQuery<BigDecimal> {
}
