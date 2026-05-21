package com.monk3.model;

import java.math.BigDecimal;
import java.util.List;

public record NumericExactQuery(
        List<BigDecimal> values
) implements ExactQuery<BigDecimal> {
}
