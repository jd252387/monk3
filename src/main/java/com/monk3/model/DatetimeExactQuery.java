package com.monk3.model;

import java.util.List;

public record DatetimeExactQuery(
        List<String> values
) implements ExactQuery<String> {
}
