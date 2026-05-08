package com.monk3.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record SearchExecutionRequest(
        @NotNull @Valid SearchQueryRequest query,
        @NotEmpty List<@NotBlank String> fields,
        @Positive Integer size
) {
}
