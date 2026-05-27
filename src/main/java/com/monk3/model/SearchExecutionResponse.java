package com.monk3.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Search results merged and normalized across backends", example = """
        {
          "results": [
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
            },
            {
              "backend": "solr-articles",
              "engine": "SOLR",
              "materialType": "article",
              "id": "article-1",
              "score": 5.0,
              "normalizedScore": 0.5,
              "fields": {
                "title": "Machine Learning Trends",
                "year": 2024,
                "author": "John Smith"
              }
            }
          ]
        }
        """)
public record SearchExecutionResponse(
        List<SearchResult> results
) {
}
