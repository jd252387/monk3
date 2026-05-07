package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextQuery.class, name = "text"),
        @JsonSubTypes.Type(value = RangeQuery.class, name = "range")
})
public sealed interface QueryPayload extends QueryData permits TextQuery, RangeQuery {
}
