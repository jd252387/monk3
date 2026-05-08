package com.monk3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchBackendTestResource implements QuarkusTestResourceLifecycleManager {
    private static final List<RecordedRequest> REQUESTS = new ArrayList<>();

    private HttpServer server;

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
                      }
                    }
                    """));
            server.start();
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            return Map.ofEntries(
                    Map.entry("monk3.search.backends.elastic-books.engine", "elasticsearch"),
                    Map.entry("monk3.search.backends.elastic-books.url", baseUrl + "/es"),
                    Map.entry("monk3.search.backends.elastic-books.index", "books"),
                    Map.entry("monk3.search.backends.elastic-books.material-types", "book"),
                    Map.entry("monk3.search.backends.solr-articles.engine", "solr"),
                    Map.entry("monk3.search.backends.solr-articles.url", baseUrl + "/solr"),
                    Map.entry("monk3.search.backends.solr-articles.collection", "articles"),
                    Map.entry("monk3.search.backends.solr-articles.material-types", "article"));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to start search backend test server", exception);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public static void reset() {
        synchronized (REQUESTS) {
            REQUESTS.clear();
        }
    }

    public static List<RecordedRequest> requests() {
        synchronized (REQUESTS) {
            return List.copyOf(REQUESTS);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        synchronized (REQUESTS) {
            REQUESTS.add(new RecordedRequest(exchange.getRequestURI().getPath(), new String(requestBody, StandardCharsets.UTF_8)));
        }

        byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    public record RecordedRequest(String path, String body) {
    }
}
