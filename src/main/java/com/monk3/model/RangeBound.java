package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.monk3.json.RangeBoundDeserializer;

@JsonDeserialize(using = RangeBoundDeserializer.class)
public record RangeBound(Object value) {
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
