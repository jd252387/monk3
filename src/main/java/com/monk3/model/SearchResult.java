package com.monk3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.search.SearchEngine;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Map;

@Schema(description = "A single search result from one backend", example = """
        {
          "backend": "elastic-books",
          "engine": "ELASTICSEARCH",
          "materialType": "book",
          "id": "book-1",
          "score": 10.0,
          "normalizedScore": 1.0,
          "fields": {
            "title": "Java Records in Practice",
            "year": 2025,
            "author": "Jane Doe"
          }
        }
        """)
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
