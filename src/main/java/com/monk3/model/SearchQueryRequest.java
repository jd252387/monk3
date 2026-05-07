package com.monk3.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SearchQueryRequest(
        String id,
        @NotBlank String name,
        @NotEmpty List<@NotBlank String> materialTypes,
        @NotNull @Valid QueryNode query
) {
}
