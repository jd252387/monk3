package com.monk3.model;

public record DatetimeRangeQuery(
        String gte,
        String gt,
        String lte,
        String lt
) implements RangeQuery<String> {
}
