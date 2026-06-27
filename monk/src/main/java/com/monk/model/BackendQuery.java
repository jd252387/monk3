package com.monk.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.search.SearchEngine;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "A request translated into a specific backend's native body", example = """
        {
          "backend": "elastic-books",
          "engine": "ELASTICSEARCH",
          "materialTypes": ["book"],
          "body": {
            "query": {
              "bool": {
                "filter": [{ "term": { "material_type": "book" } }],
                "must": [{ "match_phrase": { "book_title": "java records" } }]
              }
            },
            "size": 10,
            "_source": ["material_type", "id", "book_title"],
            "aggs": {
              "byAuthor": { "terms": { "field": "book_author", "size": 5 } }
            }
          }
        }
        """)
public record BackendQuery(
        @Schema(description = "Configured backend name") String backend,
        @Schema(description = "Search engine type") SearchEngine engine,
        @Schema(description = "Material types this query targets") List<String> materialTypes,
        @Schema(description = "The full translated request body (Elasticsearch or Solr JSON), including query, result options, and aggregations")
        ObjectNode body
) {
}
