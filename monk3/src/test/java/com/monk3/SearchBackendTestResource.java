package com.monk3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SearchBackendTestResource implements QuarkusTestResourceLifecycleManager {
    private static final long PARALLEL_REQUEST_TIMEOUT_SECONDS = 2L;
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<CountDownLatch> PARALLEL_REQUESTS = new AtomicReference<>();

    private HttpServer server;
    private ExecutorService executor;
    private Path backendsFile;
    private Path catalogFile;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/es/books/_search", exchange -> respond(exchange, """
                    {
                      "hits": {
                        "max_score": 20.0,
                        "hits": [
                          {
                            "_id": "book-1",
                            "_score": 10.0,
                            "_source": {
                              "id": "book-1",
                              "material_type": "book",
                              "book_title": "Java Records",
                              "book_year": 2025
                            }
                          }
                        ]
                      },
                      "aggregations": {
                        "byAuthor": {
                          "buckets": [
                            { "key": "Jane Doe", "doc_count": 8 },
                            { "key": "John Smith", "doc_count": 3 }
                          ]
                        },
                        "uniqueYears": { "value": 42 },
                        "byYear": {
                          "buckets": [
                            { "key": 2000.0, "doc_count": 5 },
                            { "key": 2010.0, "doc_count": 7 }
                          ]
                        },
                        "published": {
                          "buckets": {
                            "lastWeek": { "doc_count": 2 },
                            "lastMonth": { "doc_count": 9 }
                          }
                        },
                        "matchingDocs": { "doc_count": 4 }
                      }
                    }
                    """));
            server.createContext("/solr/articles/select", exchange -> respond(exchange, """
                    {
                      "response": {
                        "docs": [
                          {
                            "id": "article-1",
                            "score": 5.0,
                            "material_type": "article",
                            "article_headline": "Solr Article",
                            "article_year": 2024
                          }
                        ]
                      },
                      "facets": {
                        "count": 11,
                        "byYear": {
                          "buckets": [
                            { "val": 2020, "count": 4 },
                            { "val": 2024, "count": 6 }
                          ]
                        },
                        "uniqueYears": 7,
                        "published": {
                          "count": 11,
                          "lastWeek": { "count": 2 },
                          "lastMonth": { "count": 9 }
                        },
                        "matchingDocs": { "count": 4 }
                      }
                    }
                    """));
            server.createContext("/es/empty/_search", exchange -> respond(exchange, """
                    {
                      "hits": {
                        "max_score": null,
                        "hits": []
                      }
                    }
                    """));
            server.createContext("/solr/books/select", exchange -> respond(exchange, """
                    {
                      "response": {
                        "docs": [
                          {
                            "id": "book-2",
                            "score": 8.0,
                            "material_type": "book",
                            "book_title": "Recent Book",
                            "book_year": 2026
                          }
                        ]
                      }
                    }
                    """));
            server.createContext("/embedding/embed", exchange -> respond(exchange, """
                    {
                      "result": [
                        { "embedding_vector": [0.1, 0.2, 0.3] }
                      ]
                    }
                    """));
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.start();
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            String backendsJson = """
                    {"backends":{
                      "elastic-books":{"engine":"ELASTICSEARCH","url":"%s/es","index":"books","primaryKey":"id","physical":"config/mappings/book.mapping.json","virtual":"config/mappings/book.virtual.json"},
                      "elastic-empty":{"engine":"ELASTICSEARCH","url":"%s/es","index":"empty","primaryKey":"id","physical":"config/mappings/dataset.mapping.json"},
                      "solr-articles":{"engine":"SOLR","url":"%s/solr","collection":"articles","primaryKey":"id","physical":"config/mappings/article.mapping.json"},
                      "solr-books":{"engine":"SOLR","url":"%s/solr","collection":"books","primaryKey":"id","physical":"config/mappings/book.mapping.json","virtual":"config/mappings/book.virtual.json"}
                    }}""".formatted(baseUrl, baseUrl, baseUrl, baseUrl);
            backendsFile = Files.createTempFile("monk3-test-backends", ".json");
            Files.writeString(backendsFile, backendsJson);

            String catalogJson = """
                    {"mappings":{
                      "book": {
                        "backend":  "elastic-books",
                        "filter": {"field":"materialType","data":{"type":"text","phrases":[{"type":"phrase","value":"book"}]}},
                        "routing": [
                          {
                            "conditions": [
                              {"type": "datetimeRangeWithin", "field": "publishedAt", "period": "P30D"}
                            ],
                            "backend": "solr-books"
                          }
                        ]
                      },
                      "book_elastic": {"backend":"elastic-books","filter":{"field":"materialType","data":{"type":"text","phrases":[{"type":"phrase","value":"book_elastic"}]}}},
                      "article": {"backend":"solr-articles","filter":{"field":"materialType","data":{"type":"text","phrases":[{"type":"phrase","value":"article"}]}}},
                      "article_solr": {"backend":"solr-articles","filter":{"field":"materialType","data":{"type":"text","phrases":[{"type":"phrase","value":"article_solr"}]}}},
                      "emptyset":{"backend":"elastic-empty","filter":{"field":"materialType","data":{"type":"text","phrases":[{"type":"phrase","value":"emptyset"}]}}}
                    }}""";
            catalogFile = Files.createTempFile("monk3-test-catalog", ".json");
            Files.writeString(catalogFile, catalogJson);

            return Map.of(
                    "indexer.catalog.source", "FILE",
                    "indexer.catalog.file.backends", backendsFile.toAbsolutePath().toString(),
                    "indexer.catalog.file.config", catalogFile.toAbsolutePath().toString(),
                    "monk3.embedding.url", baseUrl + "/embedding/embed");
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to start search backend test server", exception);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.close();
        }
        deleteQuietly(backendsFile);
        deleteQuietly(catalogFile);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    public static void reset() {
        REQUESTS.clear();
    }

    public static void requireParallelRequests(int requestCount) {
        PARALLEL_REQUESTS.set(new CountDownLatch(requestCount));
    }

    public static void clearParallelRequestRequirement() {
        PARALLEL_REQUESTS.set(null);
    }

    public static List<RecordedRequest> requests() {
        return List.copyOf(REQUESTS);
    }

    public static List<String> requestPaths() {
        return REQUESTS.stream().map(RecordedRequest::path).toList();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        REQUESTS.add(new RecordedRequest(
                exchange.getRequestURI().getPath(),
                new String(requestBody, StandardCharsets.UTF_8)));
        awaitParallelRequests();

        byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    private static void awaitParallelRequests() throws IOException {
        CountDownLatch requests = PARALLEL_REQUESTS.get();
        if (requests == null) {
            return;
        }
        requests.countDown();
        try {
            if (!requests.await(PARALLEL_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for parallel backend requests");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for parallel backend requests", exception);
        }
    }

    public record RecordedRequest(String path, String body) {
    }
}
