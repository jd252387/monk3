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
    void validTextQueryLeafReturnsEchoedJson() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "id": "query-1",
                          "name": "Text query",
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
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", equalTo("query-1"))
                .body("name", equalTo("Text query"))
                .body("query.field", equalTo("title"))
                .body("query.data.type", equalTo("text"))
                .body("query.data.phrases[0]", equalTo("java records"))
                .body("query.data", not(hasKey("textType")));
    }

    @Test
    void validRangeQueryReturnsEchoedJson() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Range query",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "year",
                            "data": {
                              "type": "range",
                              "gte": 1995,
                              "lte": 2020
                            }
                          }
                        }
                        """)
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.data.type", equalTo("range"))
                .body("query.data.gte", equalTo(1995))
                .body("query.data.lte", equalTo(2020));
    }

    @Test
    void validDatetimeRangeQueryReturnsEchoedJson() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Datetime range query",
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
                        """)
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.data.type", equalTo("range"))
                .body("query.data.gte", equalTo("2024-01-01T00:00:00Z"))
                .body("query.data.lt", equalTo("2025-01-01T00:00:00Z"));
    }

    @Test
    void validNumericExactQueryReturnsEchoedJson() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Numeric exact query",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "year",
                            "data": {
                              "type": "exact",
                              "values": [1995, 2020]
                            }
                          }
                        }
                        """)
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.data.type", equalTo("exact"))
                .body("query.data.values[0]", equalTo(1995))
                .body("query.data.values[1]", equalTo(2020));
    }

    @Test
    void validDatetimeExactQueryReturnsEchoedJson() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Datetime exact query",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "publishedAt",
                            "data": {
                              "type": "exact",
                              "values": ["2024-01-01T00:00:00Z"]
                            }
                          }
                        }
                        """)
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.data.type", equalTo("exact"))
                .body("query.data.values[0]", equalTo("2024-01-01T00:00:00Z"));
    }

    @Test
    void validBooleanExactQueryReturnsEchoedJson() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Boolean exact query",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "isPublished",
                            "data": {
                              "type": "exact",
                              "values": [true, false]
                            }
                          }
                        }
                        """)
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.data.type", equalTo("exact"))
                .body("query.data.values[0]", equalTo(true))
                .body("query.data.values[1]", equalTo(false));
    }

    @Test
    void validBooleanQueryRoundTripsNestedClauses() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "Boolean query",
                          "materialTypes": ["book", "article"],
                          "query": {
                            "field": "",
                            "minimumMatch": 1,
                            "data": [
                              [
                                {
                                  "field": "title",
                                  "data": {
                                    "type": "text",
                                    "phrases": ["history"]
                                  }
                                },
                                {
                                  "field": "year",
                                  "isNot": true,
                                  "data": {
                                    "type": "range",
                                    "gt": 1800,
                                    "lte": 1900
                                  }
                                }
                              ]
                            ]
                          }
                        }
                        """)
                .when().post("/queries")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.field", equalTo(""))
                .body("query.minimumMatch", equalTo(1))
                .body("query.data[0][0].data.type", equalTo("text"))
                .body("query.data[0][1].isNot", equalTo(true))
                .body("query.data[0][1].data.gt", equalTo(1800));
    }

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
                .when().post("/queries/parse/elasticsearch")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.bool.filter[0].term.material_type", equalTo("book"))
                .body("query.bool.must[0].match_phrase.book_title", equalTo("java records"));
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
                .when().post("/queries/parse/solr")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.bool.filter[0].field.f", equalTo("material_type"))
                .body("query.bool.filter[0].field.query", equalTo("article"))
                .body("query.bool.must[0].frange.query", equalTo("article_year"))
                .body("query.bool.must[0].frange.l", equalTo(1995))
                .body("query.bool.must[0].frange.u", equalTo(2020))
                .body("query.bool.must[0].frange.incl", equalTo(true))
                .body("query.bool.must[0].frange.incu", equalTo(false));
    }

    @Test
    void combinesMultipleMaterialTypesWithEachConfiguredMapping() {
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
                .when().post("/queries/parse/elasticsearch")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.bool.minimum_should_match", equalTo(1))
                .body("query.bool.should[0].bool.filter[0].term.material_type", equalTo("book"))
                .body("query.bool.should[0].bool.must[0].match_phrase.book_title", equalTo("history"))
                .body("query.bool.should[1].bool.filter[0].term.material_type", equalTo("article"))
                .body("query.bool.should[1].bool.must[0].match_phrase.article_headline", equalTo("history"));
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
                .when().post("/queries/parse/elasticsearch")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("query.bool.must[0].nested.path", equalTo("chapters"))
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
                .when().post("/queries/parse/elasticsearch")
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
                .when().post("/queries")
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
                .when().post("/queries")
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
                .when().post("/queries")
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
                .when().post("/queries")
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

    private static void assertBadRequest(String body) {
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/queries")
                .then()
                .statusCode(400);
    }
}
