package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = BooleanQueryData.class)
@JsonSubTypes({
        @JsonSubTypes.Type(BooleanQueryData.class),
        @JsonSubTypes.Type(TextQuery.class),
        @JsonSubTypes.Type(RangeQuery.class)
})
public sealed interface QueryData permits BooleanQueryData, QueryPayload {
}
