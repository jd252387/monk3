package com.monk3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@QuarkusTestResource(SearchBackendTestResource.class)
class QueryResourceTest {
    @Test
    void parsesTextQueryToElasticsearchDslUsingConfiguredMapping() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Elasticsearch text query",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": [{ "value": "java records" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.filter[0].terms.material_type[0]", equalTo("book"))
                .body("[0].body.query.bool.must[0].match_phrase.book_title", equalTo("java records"));
    }

    @Test
    void parsesTextQueryWithMorphologyToConfiguredMorphologyFieldForElasticsearch() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Elasticsearch morphology text query",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "english",
                              "phrases": [{ "value": "java records" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].match_phrase", hasKey("book_title_en"))
                .body("[0].body.query.bool.must[0].match_phrase.book_title_en", equalTo("java records"));
    }

    @Test
    void parsesTextQueryWithMorphologyToConfiguredMorphologyFieldForSolr() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Solr morphology text query",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "english",
                              "phrases": [{ "value": "history" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].body.query.bool.must[0].field.f", equalTo("article_headline_en"))
                .body("[0].body.query.bool.must[0].field.query", equalTo("history"));
    }

    @Test
    void textQueryWithUnconfiguredMorphologyReturnsStructuredBadRequest() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Unknown morphology",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "french",
                              "phrases": [{ "value": "x" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("query_translation_failed"))
                .body("error.message", containsString("Morphology 'french' is not configured for field 'title'"));
    }

    @Test
    void exactPhraseSkipsMorphologyForElasticsearch() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Exact phrase skips morphology",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "english",
                              "phrases": [{ "value": "java records", "isExact": true }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].match_phrase", hasKey("book_title"))
                .body("[0].body.query.bool.must[0].match_phrase", not(hasKey("book_title_en")))
                .body("[0].body.query.bool.must[0].match_phrase.book_title", equalTo("java records"));
    }

    @Test
    void exactPhraseSkipsMorphologyForSolr() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Exact phrase skips morphology (Solr)",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "english",
                              "phrases": [{ "value": "history", "isExact": true }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].body.query.bool.must[0].field.f", equalTo("article_headline"))
                .body("[0].body.query.bool.must[0].field.query", equalTo("history"));
    }

    @Test
    void mixesExactAndMorphologyPhrasesIntoShouldForElasticsearch() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Mixed exact and morphology phrases",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "english",
                              "phrases": [
                                { "value": "java", "isExact": true },
                                { "value": "records" }
                              ]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].bool.should[0].match_phrase.book_title", equalTo("java"))
                .body("[0].body.query.bool.must[0].bool.should[1].match_phrase.book_title_en", equalTo("records"));
    }

    @Test
    void exactPhraseIgnoresUnconfiguredMorphology() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Exact phrase ignores unknown morphology",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "morphology": "french",
                              "phrases": [{ "value": "x", "isExact": true }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].match_phrase.book_title", equalTo("x"));
    }

    @Test
    void parsesRangeQueryToSolrJsonDslUsingConfiguredMapping() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
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
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-articles"))
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].body.query.bool.filter[0].field.f", equalTo("material_type"))
                .body("[0].body.query.bool.filter[0].field.query", equalTo("article"))
                .body("[0].body.query.bool.must[0].frange.query", equalTo("article_year"))
                .body("[0].body.query.bool.must[0].frange.l", equalTo(1995))
                .body("[0].body.query.bool.must[0].frange.u", equalTo(2020))
                .body("[0].body.query.bool.must[0].frange.incl", equalTo(true))
                .body("[0].body.query.bool.must[0].frange.incu", equalTo(false));
    }

    @Test
    void routesMultipleMaterialTypesToTheirRespectiveBackends() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Cross material query",
                          "materialTypes": ["book", "article"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": [{ "value": "history" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(2))
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].materialTypes[0]", equalTo("book"))
                .body("[0].body.query.bool.filter[0].terms.material_type[0]", equalTo("book"))
                .body("[0].body.query.bool.must[0].match_phrase.book_title", equalTo("history"))
                .body("[1].backend", equalTo("solr-articles"))
                .body("[1].engine", equalTo("SOLR"))
                .body("[1].materialTypes[0]", equalTo("article"))
                .body("[1].body.query.bool.filter[0].field.f", equalTo("material_type"))
                .body("[1].body.query.bool.filter[0].field.query", equalTo("article"))
                .body("[1].body.query.bool.must[0].field.f", equalTo("article_headline"))
                .body("[1].body.query.bool.must[0].field.query", equalTo("history"));
    }

    @Test
    void parsesSubdocumentQueryToElasticsearchNestedDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
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
                                    "phrases": [{ "value": "introduction" }]
                                  }
                                }
                              ]
                            ]
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].nested.path", equalTo("chapters"))
                .body(containsString("\"chapters.title\""))
                .body(containsString("\"introduction\""));
    }

    @Test
    void parsesSubdocumentQueryToSolrBlockJoinDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
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
                                          "phrases": [{ "value": "introduction" }]
                                        }
                                      }
                                    ]
                                  ]
                                }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentRange())))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-books"))
                .body("[0].engine", equalTo("SOLR"))
                // root -> chapters join masks against the configured root identifier, not a hardcoded mask
                .body(containsString("\"{!v=$root_identifier}\""))
                .body("[0].body.queries.root_identifier.field.f", equalTo("material_type"))
                .body("[0].body.queries.root_identifier.field.query", equalTo("book"))
                .body(containsString("\"_nest_path_\""))
                .body(containsString("\"/chapters\""))
                .body(containsString("\"title\""))
                .body(not(containsString("chapters.title")))
                .body(containsString("\"introduction\""))
                .body(not(containsString("*:* -_nest_path_:*")));
    }

    @Test
    void parsesDeeplyNestedSubdocumentQueryToSolrBlockJoinDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Deep nested chapter/page query Solr",
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
                                        "field": "pages",
                                        "data": [
                                          [
                                            {
                                              "field": "content",
                                              "data": { "type": "text", "phrases": [{ "value": "ew" }] }
                                            }
                                          ]
                                        ]
                                      }
                                    ]
                                  ]
                                }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentRange())))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-books"))
                .body("[0].engine", equalTo("SOLR"))
                // outer join (root -> chapters) masks against the configured root identifier
                .body(containsString("\"{!v=$root_identifier}\""))
                .body("[0].body.queries.root_identifier.field.f", equalTo("material_type"))
                .body("[0].body.queries.root_identifier.field.query", equalTo("book"))
                // inner join (chapters -> pages) masks against the parent chapters' nest path
                .body(containsString("\"_nest_path_:/chapters\""))
                // child queries are scoped to their own full nest paths
                .body(containsString("\"/chapters\""))
                .body(containsString("\"/chapters/pages\""))
                .body(containsString("\"content\""))
                .body(containsString("\"ew\""))
                .body(not(containsString("*:* -_nest_path_:*")));
    }

    @Test
    void parseEmitsElasticsearchResultOptionsAndAggregations() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "ES full body",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title", "year"],
                          "size": 25,
                          "aggs": {
                            "byAuthor": { "aggType": "terms", "args": { "field": "author", "size": 5 } }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].match_phrase.book_title", equalTo("java"))
                .body("[0].body.size", equalTo(25))
                .body("[0].body._source", containsInAnyOrder("material_type", "id", "book_title", "book_year"))
                .body("[0].body.aggs.byAuthor.terms.field", equalTo("book_author"))
                .body("[0].body.aggs.byAuthor.terms.size", equalTo(5));
    }

    @Test
    void parseDefaultsSizeToBackendDefaultSizeWhenOmitted() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "ES default size",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": { "type": "text", "phrases": [{ "value": "java" }] }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].body.size", equalTo(10));
    }

    @Test
    void parseEmitsSolrResultOptionsAndFacets() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Solr full body",
                            "materialTypes": ["article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "solr" }] }
                            }
                          },
                          "fields": ["title", "year"],
                          "size": 15,
                          "aggs": {
                            "byYear": { "aggType": "terms", "args": { "field": "year", "size": 5 } }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-articles"))
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].body.limit", equalTo(15))
                .body("[0].body.fields[0]", equalTo("score"))
                .body("[0].body.fields", containsInAnyOrder("score", "material_type", "id", "article_headline", "article_year"))
                .body("[0].body.facet.byYear.type", equalTo("terms"))
                .body("[0].body.facet.byYear.field", equalTo("article_year"))
                .body("[0].body.facet.byYear.limit", equalTo(5));
    }

    @Test
    void parseEmitsRangeAggregationMatchingSearchBody() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Year histogram",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "byYear": { "aggType": "range", "args": { "field": "year", "interval": 10, "from": 2000, "to": 2030 } }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].body.aggs.byYear.histogram.field", equalTo("book_year"))
                .body("[0].body.aggs.byYear.histogram.interval", equalTo(10))
                .body(containsString(
                        "\"byYear\":{\"histogram\":{\"field\":\"book_year\",\"interval\":10,\"min_doc_count\":0,"
                                + "\"hard_bounds\":{\"min\":2000,\"max\":2030},\"extended_bounds\":{\"min\":2000,\"max\":2030}}}"));
    }

    @Test
    void parseEmitsSolrSubfacetsAggregationMatchingSearchBody() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Published subfacets Solr",
                            "materialTypes": ["article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "solr" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "published": {
                              "aggType": "subfacets",
                              "args": {
                                "field": "publishedAt",
                                "filters": {
                                  "lastWeek": { "type": "range", "gt": "2026-02-01T00:00:00Z", "lt": "2026-02-07T00:00:00Z" },
                                  "lastMonth": { "type": "range", "gt": "2026-01-07T00:00:00Z", "lt": "2026-02-07T00:00:00Z" }
                                }
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-articles"))
                .body("[0].body.facet.published.type", equalTo("query"))
                .body(containsString(
                        "\"published\":{\"type\":\"query\",\"q\":\"*:*\",\"facet\":{"
                                + "\"lastWeek\":{\"type\":\"query\",\"q\":{\"frange\":{\"query\":\"article_published_at\""));
    }

    @Test
    void parseEmitsElasticsearchFilterAggregationCombiningQueryNodesWithMust() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Filter aggregation ES",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "matchingDocs": {
                              "aggType": "filter",
                              "args": {
                                "query": [
                                  { "field": "year", "data": { "type": "range", "gt": 2000, "lt": 2020 } },
                                  { "field": "title", "data": { "type": "text", "phrases": [{ "value": "machine learning" }] } }
                                ]
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].body.aggs.matchingDocs.filter.bool.must[0].range.book_year.gt", equalTo(2000))
                .body("[0].body.aggs.matchingDocs.filter.bool.must[0].range.book_year.lt", equalTo(2020))
                .body("[0].body.aggs.matchingDocs.filter.bool.must[1].match_phrase.book_title", equalTo("machine learning"))
                .body("[0].body.queries", nullValue());
    }

    @Test
    void parseEmitsSolrFilterAggregationReferencingTopLevelNamedQuery() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Filter aggregation Solr",
                            "materialTypes": ["article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "solr" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "matchingDocs": {
                              "aggType": "filter",
                              "args": {
                                "query": [
                                  { "field": "year", "data": { "type": "range", "gt": 2000, "lt": 2020 } },
                                  { "field": "title", "data": { "type": "text", "phrases": [{ "value": "machine learning" }] } }
                                ]
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-articles"))
                .body("[0].body.facet.matchingDocs.type", equalTo("query"))
                .body("[0].body.facet.matchingDocs.q", equalTo("{!v=$agg_matchingDocs}"))
                .body("[0].body.queries.agg_matchingDocs.bool.must[0].frange.query", equalTo("article_year"))
                .body("[0].body.queries.agg_matchingDocs.bool.must[0].frange.l", equalTo(2000))
                .body("[0].body.queries.agg_matchingDocs.bool.must[0].frange.incl", equalTo(false))
                .body("[0].body.queries.agg_matchingDocs.bool.must[0].frange.u", equalTo(2020))
                .body("[0].body.queries.agg_matchingDocs.bool.must[0].frange.incu", equalTo(false))
                .body("[0].body.queries.agg_matchingDocs.bool.must[1].field.f", equalTo("article_headline"))
                .body("[0].body.queries.agg_matchingDocs.bool.must[1].field.query", equalTo("machine learning"));
    }

    @Test
    void parseEmitsSingleNodeFilterAggregationWithoutMustWrapper() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Single-node filter aggregation",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "recentDocs": {
                              "aggType": "filter",
                              "args": {
                                "query": [
                                  { "field": "year", "data": { "type": "range", "gt": 2000, "lt": 2020 } }
                                ]
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].body.aggs.recentDocs.filter.range.book_year.gt", equalTo(2000))
                .body("[0].body.aggs.recentDocs.filter.bool", nullValue());
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
                                    "phrases": [{ "value": "java" }]
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
    void executesTermsAndUniqueAggregationsAgainstElasticsearch() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Faceted book search",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "size": 10,
                          "aggs": {
                            "byAuthor": { "aggType": "terms", "args": { "field": "author", "size": 5 } },
                            "uniqueYears": { "aggType": "unique", "args": { "field": "year" } }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'elastic-books'.byAuthor.buckets[0].key", equalTo("Jane Doe"))
                .body("aggregations.'elastic-books'.byAuthor.buckets[0].count", equalTo(8))
                .body("aggregations.'elastic-books'.byAuthor.buckets[1].key", equalTo("John Smith"))
                .body("aggregations.'elastic-books'.byAuthor.buckets[1].count", equalTo(3))
                .body("aggregations.'elastic-books'.uniqueYears.value", equalTo(42));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().body(), containsString(
                "\"aggs\":{\"byAuthor\":{\"terms\":{\"field\":\"book_author\",\"size\":5}},"
                        + "\"uniqueYears\":{\"cardinality\":{\"field\":\"book_year\"}}}"));
    }

    @Test
    void executesTermsAndUniqueAggregationsAgainstSolr() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Faceted article search",
                            "materialTypes": ["article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "solr" }] }
                            }
                          },
                          "fields": ["title"],
                          "size": 10,
                          "aggs": {
                            "byYear": { "aggType": "terms", "args": { "field": "year", "size": 5 } },
                            "uniqueYears": { "aggType": "unique", "args": { "field": "year" } }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'solr-articles'.byYear.buckets[0].key", equalTo(2020))
                .body("aggregations.'solr-articles'.byYear.buckets[0].count", equalTo(4))
                .body("aggregations.'solr-articles'.byYear.buckets[1].key", equalTo(2024))
                .body("aggregations.'solr-articles'.byYear.buckets[1].count", equalTo(6))
                .body("aggregations.'solr-articles'.uniqueYears.value", equalTo(7));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().body(), containsString(
                "\"facet\":{\"byYear\":{\"type\":\"terms\",\"field\":\"article_year\",\"limit\":5},"
                        + "\"uniqueYears\":\"unique(article_year)\"}"));
    }

    @Test
    void executesRangeAggregationAgainstElasticsearch() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Year histogram",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "byYear": { "aggType": "range", "args": { "field": "year", "interval": 10, "from": 2000, "to": 2030 } }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'elastic-books'.byYear.buckets[0].key", equalTo(2000.0f))
                .body("aggregations.'elastic-books'.byYear.buckets[0].count", equalTo(5))
                .body("aggregations.'elastic-books'.byYear.buckets[1].key", equalTo(2010.0f))
                .body("aggregations.'elastic-books'.byYear.buckets[1].count", equalTo(7));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().body(), containsString(
                "\"byYear\":{\"histogram\":{\"field\":\"book_year\",\"interval\":10,\"min_doc_count\":0,"
                        + "\"hard_bounds\":{\"min\":2000,\"max\":2030},\"extended_bounds\":{\"min\":2000,\"max\":2030}}}"));
    }

    @Test
    void executesRangeAggregationAgainstSolr() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Year range facet",
                            "materialTypes": ["article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "solr" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "byYear": { "aggType": "range", "args": { "field": "year", "interval": 10, "from": 2000, "to": 2030 } }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'solr-articles'.byYear.buckets[0].key", equalTo(2020))
                .body("aggregations.'solr-articles'.byYear.buckets[0].count", equalTo(4));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().body(), containsString(
                "\"byYear\":{\"type\":\"range\",\"field\":\"article_year\",\"start\":2000,\"end\":2030,\"gap\":10}"));
    }

    @Test
    void executesSubfacetsAggregationAgainstElasticsearch() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Published subfacets",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "published": {
                              "aggType": "subfacets",
                              "args": {
                                "field": "publishedAt",
                                "filters": {
                                  "lastWeek": { "type": "range", "gt": "2026-02-01T00:00:00Z", "lt": "2026-02-07T00:00:00Z" },
                                  "lastMonth": { "type": "range", "gt": "2026-01-07T00:00:00Z", "lt": "2026-02-07T00:00:00Z" }
                                }
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'elastic-books'.published.buckets[0].key", equalTo("lastWeek"))
                .body("aggregations.'elastic-books'.published.buckets[0].count", equalTo(2))
                .body("aggregations.'elastic-books'.published.buckets[1].key", equalTo("lastMonth"))
                .body("aggregations.'elastic-books'.published.buckets[1].count", equalTo(9));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().body(), containsString(
                "\"published\":{\"filters\":{\"filters\":{"
                        + "\"lastWeek\":{\"range\":{\"book_published_at\":{\"gt\":\"2026-02-01T00:00:00Z\",\"lt\":\"2026-02-07T00:00:00Z\"}}},"
                        + "\"lastMonth\":{\"range\":{\"book_published_at\":{\"gt\":\"2026-01-07T00:00:00Z\",\"lt\":\"2026-02-07T00:00:00Z\"}}}}}}"));
    }

    @Test
    void executesSubfacetsAggregationAgainstSolr() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Published subfacets Solr",
                            "materialTypes": ["article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "solr" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "published": {
                              "aggType": "subfacets",
                              "args": {
                                "field": "publishedAt",
                                "filters": {
                                  "lastWeek": { "type": "range", "gt": "2026-02-01T00:00:00Z", "lt": "2026-02-07T00:00:00Z" },
                                  "lastMonth": { "type": "range", "gt": "2026-01-07T00:00:00Z", "lt": "2026-02-07T00:00:00Z" }
                                }
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'solr-articles'.published.buckets[0].key", equalTo("lastWeek"))
                .body("aggregations.'solr-articles'.published.buckets[0].count", equalTo(2))
                .body("aggregations.'solr-articles'.published.buckets[1].key", equalTo("lastMonth"))
                .body("aggregations.'solr-articles'.published.buckets[1].count", equalTo(9));

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().body(), containsString(
                "\"published\":{\"type\":\"query\",\"q\":\"*:*\",\"facet\":{"
                        + "\"lastWeek\":{\"type\":\"query\",\"q\":{\"frange\":{\"query\":\"article_published_at\""));
    }

    @Test
    void returnsAggregationResultsPerBackend() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Cross-backend facets",
                            "materialTypes": ["book", "article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "byYear": { "aggType": "terms", "args": { "field": "year" } }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations", hasKey("elastic-books"))
                .body("aggregations", hasKey("solr-articles"))
                .body("aggregations.'elastic-books'.byYear.buckets[0].key", equalTo(2000.0f))
                .body("aggregations.'solr-articles'.byYear.buckets[0].key", equalTo(2020));
    }

    @Test
    void filterAggregationCountIsParsedFromBothBackends() {
        SearchBackendTestResource.reset();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Filter aggregation counts",
                            "materialTypes": ["book", "article"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "matchingDocs": {
                              "aggType": "filter",
                              "args": {
                                "query": [
                                  { "field": "year", "data": { "type": "range", "gt": 2000, "lt": 2020 } },
                                  { "field": "title", "data": { "type": "text", "phrases": [{ "value": "machine learning" }] } }
                                ]
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations.'elastic-books'.matchingDocs.value", equalTo(4))
                .body("aggregations.'elastic-books'.matchingDocs.buckets", nullValue())
                .body("aggregations.'solr-articles'.matchingDocs.value", equalTo(4));
    }

    @Test
    void searchWithoutAggregationsOmitsAggregationsInResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Plain search",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"]
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("aggregations", nullValue());
    }

    @Test
    void searchMatchingNoDocumentsReturnsEmptyResults() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "No matches",
                            "materialTypes": ["emptyset"],
                            "query": {
                              "field": "ds",
                              "data": { "type": "text", "phrases": [{ "value": "nonexistent" }] }
                            }
                          },
                          "fields": ["ds"]
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("results.size()", equalTo(0))
                .body("aggregations", nullValue());
    }

    @Test
    void parsesBooleanExactQueryForElasticsearchAndSolr() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Boolean exact query (ES)",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "inPrint",
                            "data": { "type": "exact", "values": [true] }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].terms.book_in_print[0]", equalTo(true));

        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Boolean exact query (Solr)",
                          "materialTypes": ["article"],
                          "query": {
                            "field": "openAccess",
                            "data": { "type": "exact", "values": [true] }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].body.query.bool.must[0].field.f", equalTo("article_open_access"))
                .body("[0].body.query.bool.must[0].field.query", equalTo(true));
    }

    @Test
    void aggregationTranslationFailuresReturnStructuredBadRequest() {
        assertAggregationTranslationError("""
                {"missing": {"aggType": "terms", "args": {"field": "missing"}}}
                """, "Aggregation field 'missing' is not defined for material type 'book'");

        assertAggregationTranslationError("""
                {"byChapter": {"aggType": "terms", "args": {"field": "chapters"}}}
                """, "Aggregations are only supported on root document fields");

        assertAggregationTranslationError("""
                {"byTitle": {"aggType": "terms", "args": {"field": "title"}}}
                """, "Aggregation type 'terms' is not supported for field 'title' with mapping type 'freetext'");

        assertAggregationTranslationError("""
                {"byTitle": {"aggType": "range", "args": {"field": "title", "interval": 10, "from": 0, "to": 100}}}
                """, "Aggregation type 'range' is not supported for field 'title' with mapping type 'freetext'");

        assertAggregationTranslationError("""
                {"byVirtual": {"aggType": "terms", "args": {"field": "recentBook"}}}
                """, "Virtual field 'recentBook' cannot be used in aggregations");
    }

    @Test
    void invalidAggregationStructuresReturnStructuredBadRequest() {
        assertAggregationStructureError("""
                {"bad": {"aggType": "geo", "args": {"field": "year"}}}
                """, "Unsupported aggregation type 'geo'");

        assertAggregationStructureError("""
                {"bad": {"aggType": "terms", "args": {"field": "year", "foo": 1}}}
                """, "Unknown terms aggregation property: foo");

        assertAggregationStructureError("""
                {"bad": {"aggType": "terms"}}
                """, "Aggregation args must be an object");

        assertAggregationStructureError("""
                {"bad": {"aggType": "terms", "args": {"size": 5}}}
                """, "Aggregation args field is required");

        assertAggregationStructureError("""
                {"bad": {"aggType": "range", "args": {"field": "year", "interval": 10, "to": 100}}}
                """, "Range aggregation requires a numeric 'from'");

        assertAggregationStructureError("""
                {"bad": {"aggType": "subfacets", "args": {"field": "publishedAt", "filters": {}}}}
                """, "Subfacets aggregation filters must not be empty");

        assertAggregationStructureError("""
                {"bad": {"aggType": "filter", "args": {"query": []}}}
                """, "Filter aggregation query must not be empty");

        assertAggregationStructureError("""
                {"bad": {"aggType": "filter", "args": {}}}
                """, "Filter aggregation query must be an array");

        assertAggregationStructureError("""
                {"bad": {"aggType": "filter", "args": {"query": [{"field": "year", "data": {"type": "range", "gt": 1}}], "foo": 1}}}
                """, "Unknown filter aggregation property: foo");
    }

    @Test
    void invalidSubfacetFilterPayloadReturnsStructuredExplanationWithPath() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Bad subfacet filter",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": {
                            "published": {
                              "aggType": "subfacets",
                              "args": {
                                "field": "publishedAt",
                                "filters": {
                                  "lastWeek": { "type": "geo" }
                                }
                              }
                            }
                          }
                        }
                        """)
                .when().post("/queries/search")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("filters.lastWeek"))
                .body("error.message", containsString("Unsupported query data type 'geo'"));
    }

    private static void assertAggregationTranslationError(String aggs, String expectedMessage) {
        assertAggregationError(aggs, "query_translation_failed", expectedMessage);
    }

    private static void assertAggregationStructureError(String aggs, String expectedMessage) {
        assertAggregationError(aggs, "invalid_query_structure", expectedMessage);
    }

    private static void assertAggregationError(String aggs, String expectedCode, String expectedMessage) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "query": {
                            "name": "Aggregation error",
                            "materialTypes": ["book"],
                            "query": {
                              "field": "title",
                              "data": { "type": "text", "phrases": [{ "value": "java" }] }
                            }
                          },
                          "fields": ["title"],
                          "aggs": %s
                        }
                        """.formatted(aggs))
                .when().post("/queries/search")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo(expectedCode))
                .body("error.message", containsString(expectedMessage));
    }

    @Test
    void invalidTranslationRequestsReturnStructuredBadRequest() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Unknown mapped field",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "missing",
                            "data": {"type": "text", "phrases": [{ "value": "x" }]}
                          }
                        }
                        """))
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
                    "data": {"type": "text", "phrases": [{ "value": "x" }]}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Missing material type",
                  "materialTypes": [],
                  "query": {
                    "field": "title",
                    "data": {"type": "text", "phrases": [{ "value": "x" }]}
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
                    "data": {"type": "text", "phrases": [{ "value": "x" }]}
                  }
                }
                """);

        assertBadRequest("""
                {
                  "name": "Invalid payload type",
                  "materialTypes": ["book"],
                  "query": {
                    "field": "title",
                    "data": {"type": "geo", "phrases": [{ "value": "x" }]}
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
                .body(parseRequest("""
                        {
                          "name": "Serialized validation helper",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": [{ "value": "java records" }],
                              "textType": true
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("query.query.data.textType"))
                .body("error.message", containsString("property 'textType' is not part of the query DSL"));
    }

    @Test
    void invalidQueryDataTypeReturnsStructuredExplanation() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Invalid payload type",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "geo",
                              "phrases": [{ "value": "java records" }]
                            }
                          }
                        }
                        """))
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
                .body(parseRequest("""
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
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("query.query.data"))
                .body("error.message", containsString("Query payload type is required"));
    }

    @Test
    void customJsonMappingExceptionsReturnStructuredExplanation() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
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
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("invalid_query_structure"))
                .body("error.message", containsString("query.query.data"))
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
                .body("required", containsInAnyOrder("query", "fields"))
                .body("properties.query.$ref", equalTo("#/$defs/SearchQuery"))
                .body("properties.aggs.$ref", equalTo("#/$defs/Aggregations"))
                .body("$defs.SearchQuery.required", containsInAnyOrder("name", "materialTypes", "query"))
                .body("$defs.Aggregations.additionalProperties.$ref", equalTo("#/$defs/Aggregation"))
                .body("$defs.Aggregation.oneOf.$ref", containsInAnyOrder(
                        "#/$defs/TermsAggregation",
                        "#/$defs/UniqueAggregation",
                        "#/$defs/RangeAggregation",
                        "#/$defs/SubfacetsAggregation",
                        "#/$defs/FilterAggregation"
                ))
                .body("$defs.TermsAggregation.properties.aggType.const", equalTo("terms"))
                .body("$defs.UniqueAggregation.properties.aggType.const", equalTo("unique"))
                .body("$defs.RangeAggregation.properties.aggType.const", equalTo("range"))
                .body("$defs.SubfacetsAggregation.properties.aggType.const", equalTo("subfacets"))
                .body("$defs.FilterAggregation.properties.aggType.const", equalTo("filter"))
                .body("$defs.FilterAggregation.properties.args.properties.query.items.$ref", equalTo("#/$defs/QueryNode"))
                .body("$defs.RangeAggregation.properties.args.required", containsInAnyOrder("field", "interval", "from", "to"))
                .body("$defs.SubfacetsAggregation.properties.args.properties.filters.additionalProperties.$ref",
                        equalTo("#/$defs/QueryPayload"))
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
                .body(parseRequest("""
                        {
                          "name": "Virtual field expansion ES",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "recentBook",
                            "data": {"type": "text", "phrases": [{ "value": "java" }]}
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.filter[0].terms.material_type[0]", equalTo("book"))
                .body("[0].body.query.bool.must[0].bool.should[0].bool.must[0].match_phrase.book_title", equalTo("java"))
                .body("[0].body.query.bool.must[0].bool.should[0].bool.must[1].range.book_year.gte", equalTo(2010))
                .body("[0].body.query.bool.must[0].bool.should[0].bool.must[1].range.book_year.lte", equalTo(2025));
    }

    @Test
    void expandsRootVirtualFieldToSolrDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Virtual field expansion Solr",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "",
                            "data": [
                              [
                                { "field": "recentBook", "data": {"type": "text", "phrases": [{ "value": "java" }]} },
                                { "field": "publishedAt", "data": {"type": "range", "gte": "%s", "lte": "%s"} }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentRange())))
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
                .body(parseRequest("""
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
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.filter[0].terms.material_type[0]", equalTo("book"))
                .body("[0].body.query.bool.must[0].nested.path", equalTo("chapters"))
                .body(containsString("\"chapters.page_count\""))
                .body(containsString("\"gte\""))
                .body(containsString("\"lte\""));
    }

    @Test
    void virtualFieldRejectsIncompatiblePayloadTypeWithError() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Wrong payload type for virtual field",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "recentBook",
                            "data": {"type": "range", "gte": 2010, "lte": 2025}
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("query_translation_failed"))
                .body("error.message", containsString("not compatible with virtual field 'recentBook'"));
    }

    @Test
    void expandsSubqueryVirtualFieldToElasticsearchNestedDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Subquery virtual field expansion ES",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "chapterSubquery",
                            "data": [
                              [
                                {
                                  "field": "title",
                                  "data": {"type": "text", "phrases": [{ "value": "introduction" }]}
                                }
                              ]
                            ]
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.must[0].nested.path", equalTo("chapters"))
                .body(containsString("\"chapters.title\""))
                .body(containsString("\"introduction\""));
    }

    @Test
    void expandsSubqueryVirtualFieldToSolrBlockJoinDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Subquery virtual field expansion Solr",
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
                                  "field": "chapterSubquery",
                                  "data": [
                                    [
                                      {
                                        "field": "title",
                                        "data": {"type": "text", "phrases": [{ "value": "introduction" }]}
                                      }
                                    ]
                                  ]
                                }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentRange())))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-books"))
                .body("[0].engine", equalTo("SOLR"))
                .body(containsString("\"{!v=$root_identifier}\""))
                .body("[0].body.queries.root_identifier.field.f", equalTo("material_type"))
                .body("[0].body.queries.root_identifier.field.query", equalTo("book"))
                .body(containsString("\"_nest_path_\""))
                .body(containsString("\"/chapters\""))
                .body(containsString("\"introduction\""));
    }

    @Test
    void subqueryVirtualFieldRejectsLeafPayloadWithError() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Leaf payload for subquery virtual field",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "chapterSubquery",
                            "data": {"type": "text", "phrases": [{ "value": "introduction" }]}
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("query_translation_failed"))
                .body("error.message", containsString("requires boolean query data"));
    }

    @Test
    void expandsPredicateVirtualFieldWithoutDataToElasticsearchDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Predicate virtual field expansion ES",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "twentyFirstCentury"
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].body.query.bool.filter[0].terms.material_type[0]", equalTo("book"))
                .body("[0].body.query.bool.must[0].range.book_year.gte", equalTo(2000));
    }

    @Test
    void expandsPredicateVirtualFieldWithoutDataToSolrDsl() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Predicate virtual field expansion Solr",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "",
                            "data": [
                              [
                                { "field": "twentyFirstCentury" },
                                { "field": "publishedAt", "data": {"type": "range", "gte": "%s", "lte": "%s"} }
                              ]
                            ]
                          }
                        }
                        """.formatted(recentRange())))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("solr-books"))
                .body("[0].engine", equalTo("SOLR"))
                .body(containsString("\"book_year\""))
                .body(containsString("2000"));
    }

    @Test
    void negatesPredicateVirtualField() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Negated predicate virtual field",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "twentyFirstCentury",
                            "isNot": true
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].body.query.bool.must[0].bool.must_not[0].range.book_year.gte", equalTo(2000));
    }

    @Test
    void predicateVirtualFieldRejectsDataPayloadWithError() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Predicate virtual field with data",
                          "materialTypes": ["book"],
                          "query": {
                            "field": "twentyFirstCentury",
                            "data": {"type": "text", "phrases": [{ "value": "java" }]}
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("query_translation_failed"))
                .body("error.message", containsString("Predicate virtual field 'twentyFirstCentury' does not accept a data payload"));
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
                        """.formatted(recentRange()))
                .when().post("/queries/search")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("results[0].backend", equalTo("solr-books"));

        assertThat(SearchBackendTestResource.requestPaths(), hasItem("/solr/books/select"));
        assertThat(SearchBackendTestResource.requestPaths(), not(hasItem("/es/books/_search")));
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

        assertThat(SearchBackendTestResource.requestPaths(), hasItem("/es/books/_search"));
        assertThat(SearchBackendTestResource.requestPaths(), not(hasItem("/solr/books/select")));
    }

    @Test
    void mergesQueriesWhenMultipleMaterialTypesResolveToSameElasticsearchBackend() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Merged material types ES",
                          "materialTypes": ["book", "book_elastic"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": [{ "value": "java records" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(1))
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].engine", equalTo("ELASTICSEARCH"))
                .body("[0].materialTypes", containsInAnyOrder("book", "book_elastic"))
                .body("[0].body.query.bool.filter[0].terms.material_type", containsInAnyOrder("book", "book_elastic"))
                .body("[0].body.query.bool.must[0].match_phrase.book_title", equalTo("java records"));
    }

    @Test
    void mergesQueriesWhenMultipleMaterialTypesResolveToSameSolrBackend() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Merged material types Solr",
                          "materialTypes": ["article", "article_solr"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": [{ "value": "solr search" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(1))
                .body("[0].backend", equalTo("solr-articles"))
                .body("[0].engine", equalTo("SOLR"))
                .body("[0].materialTypes", containsInAnyOrder("article", "article_solr"))
                .body("[0].body.query.bool.filter[0].bool.should[0].field.f", equalTo("material_type"))
                .body("[0].body.query.bool.filter[0].bool.should[0].field.query", equalTo("article"))
                .body("[0].body.query.bool.filter[0].bool.should[1].field.f", equalTo("material_type"))
                .body("[0].body.query.bool.filter[0].bool.should[1].field.query", equalTo("article_solr"))
                .body("[0].body.query.bool.filter[0].bool.mm", equalTo(1))
                .body("[0].body.query.bool.must[0].field.f", equalTo("article_headline"))
                .body("[0].body.query.bool.must[0].field.query", equalTo("solr search"));
    }

    @Test
    void mergesSearchBackendRequestsWhenMultipleMaterialTypesResolveToSameBackend() {
        SearchBackendTestResource.reset();
        SearchBackendTestResource.requireParallelRequests(1);

        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {
                              "query": {
                                "name": "Merged search",
                                "materialTypes": ["book", "book_elastic"],
                                "query": {
                                  "field": "title",
                                  "data": {
                                    "type": "text",
                                    "phrases": [{ "value": "java" }]
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
                    .body("results.size()", equalTo(1))
                    .body("results[0].backend", equalTo("elastic-books"))
                    .body("results[0].id", equalTo("book-1"))
                    .body("results[0].score", equalTo(10.0f))
                    .body("results[0].fields.title", equalTo("Java Records"))
                    .body("results[0].fields.year", equalTo(2025));
        } finally {
            SearchBackendTestResource.clearParallelRequestRequirement();
        }

        List<SearchBackendTestResource.RecordedRequest> requests = SearchBackendTestResource.requests();
        assertThat(requests.size(), equalTo(1));
        assertThat(requests.getFirst().path(), equalTo("/es/books/_search"));
        assertThat(
                requests.getFirst().body(),
                containsString("\"terms\":{\"material_type\":[\"book\",\"book_elastic\"]}"));
    }

    @Test
    void preservesSeparateBackendQueriesWhenMaterialTypesResolveToDifferentBackends() {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest("""
                        {
                          "name": "Still separate",
                          "materialTypes": ["book", "article"],
                          "query": {
                            "field": "title",
                            "data": {
                              "type": "text",
                              "phrases": [{ "value": "history" }]
                            }
                          }
                        }
                        """))
                .when().post("/queries/parse")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", equalTo(2))
                .body("[0].backend", equalTo("elastic-books"))
                .body("[0].materialTypes[0]", equalTo("book"))
                .body("[1].backend", equalTo("solr-articles"))
                .body("[1].materialTypes[0]", equalTo("article"));
    }

    /** Lower (7 days ago) and upper (now) bounds for a "recently published" range, for use with formatted(). */
    private static Object[] recentRange() {
        Instant now = Instant.now();
        return new Object[] {now.minus(7, ChronoUnit.DAYS), now};
    }

    private static void assertBadRequest(String body) {
        given()
                .contentType(ContentType.JSON)
                .body(parseRequest(body))
                .when().post("/queries/parse")
                .then()
                .statusCode(400);
    }

    /** Wraps a query request in the SearchExecutionRequest envelope shared by /queries/parse and /queries/search. */
    private static String parseRequest(String query) {
        return """
                {
                  "query": %s,
                  "fields": ["title"]
                }
                """.formatted(query);
    }
}
