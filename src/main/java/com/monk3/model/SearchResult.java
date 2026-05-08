package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.search.SearchEngine;

import java.util.Map;

public record SearchResult(
        String backend,
        SearchEngine engine,
        String materialType,
        String id,
        double score,
        double normalizedScore,
        Map<String, JsonNode> fields
) {
}
