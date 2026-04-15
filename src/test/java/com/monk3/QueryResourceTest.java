package com.monk3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@QuarkusTestResource(SearchBackendTestResource.class)
class QueryResourceTest {
    @Test
    void parsesTextQueryToElasticsearchDslUsingConfiguredMapping() {
        given()
                .contentType(ContentType.JSON)
                .body("""
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
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].query.bool.filter[0].term.material_type", equalTo("book"))
                .body("[0].query.bool.must[0].match_phrase.book_title", equalTo("java records"));
    }

    @Test
    void parsesRangeQueryToSolrJsonDslUsingConfiguredMapping() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Solr range query",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "year",
                            "data": {
                              "type": "range",
                              "gte": 1995,
                              "lt": 2020
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-articles"))
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].query.bool.filter[0].field.f", equalTo("material_type"))
                .body("[0].query.bool.filter[0].field.query", equalTo("article"))
                .body("[0].query.bool.must[0].frange.query", equalTo("article_year"))
                .body("[0].query.bool.must[0].frange.l", equalTo(1995))
                .body("[0].query.bool.must[0].frange.u", equalTo(2020))
                .body("[0].query.bool.must[0].frange.incl", equalTo(true))
                .body("[0].query.bool.must[0].frange.incu", equalTo(false));
    }

    @Test
    void routesMultipleMaterialTypesToTheirRespectiveBackends() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Cross material query",
                          "materialTypes": ["book", "article"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": ["history"]
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(2))
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].materialTypes[0]", equalTo("book"))
                .body("[0].query.bool.filter[0].term.material_type", equalTo("book"))
                .body("[0].query.bool.must[0].match_phrase.book_title", equalTo("history"))
                .body("[1].backend", equalTo("solr-articles"))
                .body("[1].engine", equalTo("SOLR"))
                .body("[1].materialTypes[0]", equalTo("article"))
                .body("[1].query.bool.filter[0].field.f", equalTo("material_type"))
                .body("[1].query.bool.filter[0].field.query", equalTo("article"))
                .body("[1].query.bool.must[0].field.f", equalTo("article_headline"))
                .body("[1].query.bool.must[0].field.query", equalTo("history"));
    }

    @Test
    void parsesSubdocumentQueryToElasticsearchNestedDsl() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Nested chapter query",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "chapters",
                            "data": [
                              [
                                {
                                  "field": "title",
                                  "data": {
                                    "type": "text",
                                    "phrases": ["introduction"]
                                  }
                                }
                              ]
                            ]
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].query.bool.must[0].nested.path", equalTo("chapters"))
                .body(containsString("\"chapters.title\""))
                .body(containsString("\"introduction\""));
    }

    @Test
    void parsesSubdocumentQueryToSolrBlockJoinDsl() {
        String recentLower = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS).toString();
        String recentUpper = java.time.Instant.now().toString();
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Nested chapter query Solr",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "",
                            "data": [
                              [
                                {
                                  "field": "publishedAt",
                                  "data": { "type": "range", "gte": "%s", "lte": "%s" }
                                },
                                {
                                  "field": "chapters",
                                  "data": [
                                    [
                                      {
                                        "field": "title",
                                        "data": {
                                          "type": "text",
                                          "phrases": ["introduction"]
                                        }
                                      }
                                    ]
                                  ]
                                }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentLower, recentUpper))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-books"))
                .body("[0].engine", equalTo("SOLR"))
                .body(containsString("\"*:* -_nest_path_:*\""))
                .body(containsString("\"chapters.title\""))
                .body(containsString("\"introduction\""));
    }

    @Test
    void executesQueryAcrossMultipleBackendsAndMergesNormalizedResults() {
        SearchBackendTestResource.reset();
        SearchBackendTestResource.requireParallelRequests(2);

        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "query": {
                                "name": "Merged search",
                                "materialTypes": ["book", "article"],
                                "query": {
                                  "field": "title",
                                  "data": {
                                    "type": "text",
                                    "phrases": ["java"]
                                  }
                                }
                              },
                              "fields": ["title", "year"],
                              "size": 10
                            }
                            """)
                    .when().post("/queries/search")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("results.size()", equalTo(2))
                    .body("results[0].backend", equalTo("solr-articles"))
                    .body("results[0].materialType", equalTo("article"))
                    .body("results[0].id", equalTo("article-1"))
                    .body("results[0].score", equalTo(5.0f))
                    .body("results[0].normalizedScore", equalTo(1.0f))
                    .body("results[0].fields.title", equalTo("Solr Article"))
                    .body("results[0].fields.year", equalTo(2024))
                    .body("results[1].backend", equalTo("elastic-books"))
                    .body("results[1].materialType", equalTo("book"))
                    .body("results[1].id", equalTo("book-1"))
                    .body("results[1].score", equalTo(10.0f))
                    .body("results[1].normalizedScore", equalTo(0.5f))
                    .body("results[1].fields.title", equalTo("Java Records"))
                    .body("results[1].fields.year", equalTo(2025));
        } finally {
            SearchBackendTestResource.clearParallelRequestRequirement();
        }

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(2));
        Map<String, String> requestBodiesByPath = new LinkedHashMap<>();
        for (SearchBackendTestResource.RecordedRequest request : requests) {
            requestBodiesByPath.put(request.path(), request.body());
        }
        assertThat(requestBodiesByPath.keySet(), containsInAnyOrder("/es/books/_search", "/solr/articles/select"));
        assertThat(
                requestBodiesByPath.get("/es/books/_search"),
                containsString("\"_source\":[\"material_type\",\"id\",\"book_title\",\"book_year\"]"));
        assertThat(
                requestBodiesByPath.get("/es/books/_search"),
                containsString("\"match_phrase\":{\"book_title\":\"java\"}"));
        assertThat(
                requestBodiesByPath.get("/solr/articles/select"),
                containsString("\"fields\":[\"score\",\"material_type\",\"id\",\"article_headline\",\"article_year\"]"));
        assertThat(
                requestBodiesByPath.get("/solr/articles/select"),
                containsString("\"field\":{\"f\":\"article_headline\",\"query\":\"java\"}"));
    }

    @Test
    void invalidTranslationRequestsReturnStructuredBadRequest() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Unknown mapped field",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "missing",
                            "data": {"type": "text", "phrases": ["x"]}
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("query_translation_failed"))
                .body("error.message", containsString("Field 'missing' is not defined"));
    }

    @Test
    void invalidRequestsReturnBadRequest() {
        assertBadRequest("""
                {
                  "materialTypes": ["book"],
                  "query": {
                    "field": "title",
                    "data": {"type": "text", "phrases": ["x"]}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Missing material type",
                  "materialTypes": [],
                  "query": {
                    "field": "title",
                    "data": {"type": "text", "phrases": ["x"]}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Unknown property",
                  "materialTypes": ["book"],
                  "extra": true,
                  "query": {
                    "field": "title",
                    "data": {"type": "text", "phrases": ["x"]}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Invalid payload type",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "title",
                    "data": {"type": "geo", "phrases": ["x"]}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Missing bounds",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range"}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Missing upper bound",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range", "gte": 1}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Conflicting lower bounds",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range", "gte": 1, "gt": 2, "lte": 10}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Conflicting upper bounds",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range", "gte": 1, "lte": 10, "lt": 9}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Mixed range bound types",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range", "gte": 1, "lte": "2020-01-01T00:00:00Z"}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Empty exact values",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "exact", "values": []}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Mixed exact value types",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "exact", "values": [1, "2020-01-01T00:00:00Z"]}
                  }
                }
                """);
    }

    @Test
    void invalidQuerySerializationFieldsReturnStructuredExplanation() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Serialized validation helper",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": ["java records"],
                              "textType": true
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("query.data.textType"))
                .body("error.message", containsString("property 'textType' is not part of the query DSL"));
    }

    @Test
    void invalidQueryDataTypeReturnsStructuredExplanation() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Invalid payload type",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "geo",
                              "phrases": ["java records"]
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("Unsupported query data type 'geo'"))
                .body("error.message", containsString("Supported query data types are 'text', 'range', and 'exact'"));
    }

    @Test
    void missingQueryDataTypeReturnsStructuredExplanation() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Missing payload type",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "year",
                            "data": {
                              "gte": 1995,
                              "lte": 2020
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("query.data"))
                .body("error.message", containsString("Query payload type is required"));
    }

    @Test
    void customJsonMappingExceptionsReturnStructuredExplanation() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Missing range bounds",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "year",
                            "data": {
                              "type": "range"
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("query.data"))
                .body("error.message", containsString("Range query requires at least one bound"));
    }

    @Test
    void generatedSchemaContainsDslContract() {
        given()
                .accept("application/schema+json")
                .when().get("/queries/schema")
                .then()
                .statusCode(200)
                .contentType("application/schema+json")
                .body("$schema", equalTo("https://json-schema.org/draft/2020-12/schema"))
                .body("required", containsInAnyOrder("name", "materialTypes", "query"))
                .body("$defs.QueryPayload.oneOf.$ref", containsInAnyOrder(
                        "#/$defs/TextQuery",
                        "#/$defs/RangeQuery",
                        "#/$defs/ExactQuery"
                ))
                .body("$defs.TextQuery.properties.type.const", equalTo("text"))
                .body("$defs.RangeQuery.oneOf.$ref", containsInAnyOrder(
                        "#/$defs/NumericRangeQuery",
                        "#/$defs/DatetimeRangeQuery"
                ))
                .body("$defs.ExactQuery.oneOf.$ref", containsInAnyOrder(
                        "#/$defs/NumericExactQuery",
                        "#/$defs/DatetimeExactQuery",
                        "#/$defs/BooleanExactQuery"
                ))
                .body("$defs.NumericExactQuery.properties.values.items.type", equalTo("number"))
                .body("$defs.DatetimeExactQuery.properties.values.items.type", equalTo("string"))
                .body("$defs.BooleanExactQuery.properties.values.items.type", equalTo("boolean"))
                .body("$defs.NumericExactQuery.properties.type.const", equalTo("exact"))
                .body("$defs.NumericRangeQuery.properties.type.const", equalTo("range"))
                .body("$defs.NumericRangeQuery.properties.lte.type", equalTo("number"))
                .body("$defs.DatetimeRangeQuery.properties.lte.type", equalTo("string"))
                .body("$defs.NumericRangeQuery", hasKey("allOf"))
                .body("$defs.NumericRangeQuery", hasKey("not"))
                .body("$defs.NumericRangeQuery.allOf[0].anyOf.required[0]", hasItem("gte"))
                .body("$defs.NumericRangeQuery.allOf[1].anyOf.required[0]", hasItem("lte"))
                .body("$defs.NumericRangeQuery.not.anyOf.required[0]", hasItem("gte"))
                .body("$defs.NumericRangeQuery.not.anyOf.required[0]", hasItem("gt"));
    }

    @Test
    void expandsRootVirtualFieldToElasticsearchDsl() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Virtual field expansion ES",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "recentBook",
                            "data": {"type": "text", "phrases": ["java"]}
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].query.bool.filter[0].term.material_type", equalTo("book"))
                .body("[0].query.bool.must[0].bool.should[0].bool.must[0].match_phrase.book_title", equalTo("java"))
                .body("[0].query.bool.must[0].bool.should[0].bool.must[1].range.book_year.gte", equalTo(2010))
                .body("[0].query.bool.must[0].bool.should[0].bool.must[1].range.book_year.lte", equalTo(2025));
    }

    @Test
    void expandsRootVirtualFieldToSolrDsl() {
        String recentLower = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS).toString();
        String recentUpper = java.time.Instant.now().toString();
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Virtual field expansion Solr",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "",
                            "data": [
                              [
                                { "field": "recentBook", "data": {"type": "text", "phrases": ["java"]} },
                                { "field": "publishedAt", "data": {"type": "range", "gte": "%s", "lte": "%s"} }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentLower, recentUpper))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-books"))
                .body("[0].engine", equalTo("SOLR"))
                .body(containsString("\"book_title\""))
                .body(containsString("\"java\""))
                .body(containsString("\"book_year\""))
                .body(containsString("2010"))
                .body(containsString("2025"));
    }

    @Test
    void expandsSubdocumentVirtualFieldToElasticsearchNestedDsl() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Subdocument virtual field expansion",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "chapters",
                            "data": [
                              [
                                {
                                  "field": "shortChapter",
                                  "data": {"type": "range", "gte": 5, "lte": 20}
                                }
                              ]
                            ]
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].query.bool.filter[0].term.material_type", equalTo("book"))
                .body("[0].query.bool.must[0].nested.path", equalTo("chapters"))
                .body(containsString("\"chapters.page_count\""))
                .body(containsString("\"gte\""))
                .body(containsString("\"lte\""));
    }

    @Test
    void virtualFieldRejectsIncompatiblePayloadTypeWithError() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Wrong payload type for virtual field",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "recentBook",
                            "data": {"type": "range", "gte": 2010, "lte": 2025}
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("query_translation_failed"))
                .body("error.message", containsString("not compatible with virtual field 'recentBook'"));
    }

    @Test
    void routesBookQueryToSolrBooksWhenPublishedAtRangeIsRecent() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Recent books search",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "publishedAt",
                              "data": {
                                "type": "range",
                                "gte": "%s",
                                "lte": "%s"
                              }
                            }
                          },
                          "fields": ["title"],
                          "size": 5
                        }
                        """.formatted(
                        java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS).toString(),
                        java.time.Instant.now().toString()))
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("results[0].backend", equalTo("solr-books"));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.stream().map(SearchBackendTestResource.RecordedRequest::path).toList(),
                hasItem("/solr/books/select"));
        assertThat(requests.stream().map(SearchBackendTestResource.RecordedRequest::path).toList(),
                not(hasItem("/es/books/_search")));
    }

    @Test
    void routesBookQueryToElasticBooksWhenPublishedAtRangeIsOld() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Old books search",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "publishedAt",
                              "data": {
                                "type": "range",
                                "gte": "2020-01-01T00:00:00Z",
                                "lte": "2020-12-31T00:00:00Z"
                              }
                            }
                          },
                          "fields": ["title"],
                          "size": 5
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("results[0].backend", equalTo("elastic-books"));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.stream().map(SearchBackendTestResource.RecordedRequest::path).toList(),
                hasItem("/es/books/_search"));
        assertThat(requests.stream().map(SearchBackendTestResource.RecordedRequest::path).toList(),
                not(hasItem("/solr/books/select")));
    }

    private static void assertBadRequest(String body) {
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/queries/parse")
                .then()
                .statusCode(400);
    }
}
