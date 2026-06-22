package com.monk3.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "A search request that executes a query across configured backends and returns merged results", example = """
        {
          "query": {
            "id": "q1",
            "name": "Recent ML publications",
            "materialTypes": ["book", "article"],
            "query": {
              "field": "",
              "data": [
                [
                  {
                    "field": "title",
                    "data": { "type": "text", "phrases": [{ "value": "machine learning" }] }
                  }
                ],
                [
                  {
                    "field": "year",
                    "data": { "type": "range", "gte": 2020, "lte": 2025 }
                  }
                ]
              ]
            }
          },
          "fields": ["title", "year", "author"],
          "size": 20,
          "aggs": {
            "byAuthor": {
              "aggType": "terms",
              "args": { "field": "author", "size": 10 }
            },
            "published": {
              "aggType": "subfacets",
              "args": {
                "field": "publishedAt",
                "filters": {
                  "lastYear": { "type": "range", "gte": "2025-01-01T00:00:00Z", "lt": "2026-01-01T00:00:00Z" }
                }
              }
            }
          }
        }
        """)
public record SearchExecutionRequest(
        @NotNull @Valid @Schema(description = "The search query to execute") SearchQueryRequest query,
        @NotEmpty @Schema(description = "Field names to project in the response") List<@NotBlank String> fields,
        @Positive @Schema(description = "Maximum number of results to return per backend", example = "20") Integer size,
        @Schema(description = "Optional named facets/aggregations over root document fields, computed per backend")
        Map<@NotBlank String, @NotNull @Valid Aggregation> aggs
) {
}
