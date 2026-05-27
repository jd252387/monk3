package com.monk3.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.SearchEngine;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "A query translated into a specific backend's native DSL", example = """
        {
          "backend": "elastic-books",
          "engine": "ELASTICSEARCH",
          "materialTypes": ["book"],
          "query": {
            "query": {
              "bool": {
                "filter": [{ "term": { "material_type": "book" } }],
                "must": [{ "match_phrase": { "book_title": "java records" } }]
              }
            }
          }
        }
        """)
public record BackendQuery(
        @Schema(description = "Configured backend name") String backend,
        @Schema(description = "Search engine type") SearchEngine engine,
        @Schema(description = "Material types this query targets") List<String> materialTypes,
        @Schema(description = "The translated query body (Elasticsearch or Solr JSON)") ObjectNode query
) {
}
