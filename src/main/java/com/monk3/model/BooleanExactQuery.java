package com.monk3.model;

import java.util.List;

public record BooleanExactQuery(
        List<Boolean> values
) implements ExactQuery<Boolean> {
}
