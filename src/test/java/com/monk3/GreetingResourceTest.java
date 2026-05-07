package com.monk3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class GreetingResourceTest {
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
                .body("query.data.phrases[0]", equalTo("java records"));
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
                              "lte": "2020"
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
                .body("query.data.lte", equalTo("2020"));
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
                                    "gt": 1800
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
                  "name": "Conflicting lower bounds",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range", "gte": 1, "gt": 2}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Conflicting upper bounds",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "year",
                    "data": {"type": "range", "lte": 10, "lt": 9}
                  }
                }
                """);
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
                .body("$defs.QueryPayload.oneOf.$ref", containsInAnyOrder("#/$defs/TextQuery", "#/$defs/RangeQuery"))
                .body("$defs.TextQuery.properties.type.const", equalTo("text"))
                .body("$defs.RangeQuery.properties.type.const", equalTo("range"))
                .body("$defs.RangeQuery.properties.lte.type", containsInAnyOrder("number", "string"))
                .body("$defs.RangeQuery", hasKey("anyOf"))
                .body("$defs.RangeQuery", hasKey("not"))
                .body("$defs.RangeQuery.anyOf.required[0]", hasItem("gte"))
                .body("$defs.RangeQuery.not.anyOf.required[0]", hasItem("gte"))
                .body("$defs.RangeQuery.not.anyOf.required[0]", hasItem("gt"))
                .body("$defs.RangeQuery.properties", not(hasKey("lte.type")));
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
