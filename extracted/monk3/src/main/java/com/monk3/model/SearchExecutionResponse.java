package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

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
          ],
          "aggregations": {
            "elastic-books": {
              "byAuthor": {
                "buckets": [
                  { "key": "Jane Doe", "count": 8 },
                  { "key": "John Smith", "count": 3 }
                ]
              },
              "uniqueYears": { "value": 42 }
            }
          }
        }
        """)
public record SearchExecutionResponse(
        List<SearchResult> results,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Normalized aggregation results keyed by backend name, then by aggregation name; only present when the request declares aggs")
        Map<String, Map<String, AggregationResult>> aggregations
) {
}
