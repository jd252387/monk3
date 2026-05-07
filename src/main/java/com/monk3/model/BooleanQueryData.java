package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BooleanQueryData(
        @NotNull List<@NotNull List<@NotNull @Valid QueryNode>> clauses
) implements QueryData {
    @JsonValue
    public List<List<QueryNode>> jsonValue() {
        return clauses;
    }
}
