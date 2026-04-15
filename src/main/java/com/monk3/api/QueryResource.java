package com.monk3.api;

import com.monk3.model.BackendQuery;
import com.monk3.model.SearchExecutionRequest;
import com.monk3.model.SearchExecutionResponse;
import com.monk3.model.SearchQueryRequest;
import com.monk3.search.QueryTranslationService;
import com.monk3.search.SearchExecutionService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.InputStream;
import java.util.List;

@Path("/queries")
@RunOnVirtualThread
@RequiredArgsConstructor
@Tag(name = "Queries", description = "Translate and execute search queries using the monk3 DSL")
public class QueryResource {
    private static final String SCHEMA_MEDIA_TYPE = "application/schema+json";

    private final QueryTranslationService queryTranslationService;
    private final SearchExecutionService searchExecutionService;

    @POST
    @Path("/parse")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Translate a query", description = "Translates a monk3 query into each configured backend's native DSL (Elasticsearch or Solr).")
    @RequestBody(required = true, content = @Content(examples = {
            @ExampleObject(name = "Text search",
                    summary = "Simple phrase search → ES match_phrase / Solr field query",
                    value = """
                            {
                              "name": "Elasticsearch text query",
                              "materialTypes": ["book"],
                              "query": {
                                "field": "title",
                                "data": {
                                  "type": "text",
                                  "phrases": ["java records"]
                                }
                              }
                            }
                            """),
            @ExampleObject(name = "Datetime range",
                    summary = "Datetime range query on a date field",
                    value = """
                            {
                              "name": "Articles published in 2024",
                              "materialTypes": ["article"],
                              "query": {
                                "field": "publishedAt",
                                "data": {
                                  "type": "range",
                                  "gte": "2024-01-01T00:00:00Z",
                                  "lt": "2025-01-01T00:00:00Z"
                                }
                              }
                            }
                            """),
            @ExampleObject(name = "Exact match",
                    summary = "Exact value match for specific years",
                    value = """
                            {
                              "name": "Articles from 1995 or 2020",
                              "materialTypes": ["article"],
                              "query": {
                                "field": "year",
                                "data": {
                                  "type": "exact",
                                  "values": [1995, 2020]
                                }
                              }
                            }
                            """),
            @ExampleObject(name = "Subdocument / nested",
                    summary = "Nested subdocument query on chapters",
                    value = """
                            {
                              "name": "Nested chapter query",
                              "materialTypes": ["book"],
                              "query": {
                                "field": "chapters",
                                "data": [
                                  [
                                    {
                                      "field": "title",
                                      "data": { "type": "text", "phrases": ["introduction"] }
                                    }
                                  ]
                                ]
                              }
                            }
                            """)
    }))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Translated query per backend"),
            @APIResponse(responseCode = "400", description = "Translation failed (unknown field, type mismatch, etc.)",
                    content = @Content(schema = @Schema(example = """
                            {
                              "error": {
                                "code": "query_translation_failed",
                                "message": "Field 'missing' is not defined in any mapping for material type 'book'"
                              }
                            }
                            """)))
    })
    public List<BackendQuery> parseQuery(@Valid SearchQueryRequest request) {
        return queryTranslationService.translateByBackend(request);
    }

    @POST
    @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Execute a search", description = "Fans out the query to all configured backends, merges results, and normalizes scores.")
    @RequestBody(required = true, content = @Content(examples = {
            @ExampleObject(name = "Cross-backend search",
                    summary = "Boolean query across books and articles, projecting title/year/author",
                    value = """
                            {
                              "query": {
                                "name": "Recent ML publications",
                                "materialTypes": ["book", "article"],
                                "query": {
                                  "field": "",
                                  "data": [
                                    [
                                      {
                                        "field": "title",
                                        "data": { "type": "text", "phrases": ["machine learning"] }
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
                              "size": 20
                            }
                            """),
            @ExampleObject(name = "Simple text search",
                    summary = "Single-backend phrase search with field projection",
                    value = """
                            {
                              "query": {
                                "name": "Find Java books",
                                "materialTypes": ["book"],
                                "query": {
                                  "field": "title",
                                  "data": { "type": "text", "phrases": ["java"] }
                                }
                              },
                              "fields": ["title", "year"],
                              "size": 10
                            }
                            """)
    }))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Merged, score-normalized results"),
            @APIResponse(responseCode = "502", description = "Backend search execution failed",
                    content = @Content(schema = @Schema(example = """
                            {
                              "error": {
                                "code": "search_execution_failed",
                                "message": "Elasticsearch backend 'elastic-books' returned HTTP 503"
                              }
                            }
                            """)))
    })
    public SearchExecutionResponse search(@Valid SearchExecutionRequest request) {
        return searchExecutionService.search(request);
    }

    @GET
    @Path("/schema")
    @Produces(SCHEMA_MEDIA_TYPE)
    @Operation(summary = "Query DSL JSON Schema", description = "Returns the JSON Schema that fully describes the monk3 query DSL.")
    @APIResponse(responseCode = "200", description = "JSON Schema document (draft 2020-12)")
    public Response querySchema() {
        InputStream inputStream = QueryResource.class
                .getClassLoader()
                .getResourceAsStream("search-query-dsl.schema.json");
        if (inputStream == null) {
            throw new IllegalStateException("search-query-dsl.schema.json was not found on the classpath");
        }

        return Response.ok(inputStream, SCHEMA_MEDIA_TYPE).build();
    }
}
