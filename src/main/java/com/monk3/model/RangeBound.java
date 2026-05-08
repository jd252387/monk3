package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record RangeBound(Object value) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public RangeBound {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException("Range bound must be a string or number");
        }
    }

    @JsonValue
    public Object jsonValue() {
        return value;
    }
}
