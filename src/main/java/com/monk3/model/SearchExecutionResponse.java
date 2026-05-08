package com.monk3.model;

import java.util.List;

public record SearchExecutionResponse(
        List<SearchResult> results
) {
}
