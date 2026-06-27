package com.monk.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.mapping.EmbeddingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Calls the external embedding API to turn free text into a dense vector. The API accepts
 * {@code {"texts":["<text>"]}} and returns {@code {"result":[{"embedding_vector":[...]}]}}. Used while
 * translating {@code knnFlat} queries; the returned vector is inlined into the engine query.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class EmbeddingClient {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final EmbeddingConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Returns the {@code embedding_vector} array node for the given text, or throws on any failure. */
    public JsonNode embed(String text) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putArray("texts").add(text);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.url()))
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QueryTranslationException("Embedding API returned HTTP " + response.statusCode());
            }
            return extractVector(objectMapper.readTree(response.body()));
        } catch (IOException exception) {
            throw new QueryTranslationException("Embedding API could not be reached or returned invalid JSON", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QueryTranslationException("Embedding API request was interrupted", exception);
        }
    }

    private static JsonNode extractVector(JsonNode root) {
        JsonNode vector = root.path("result").path(0).path("embedding_vector");
        if (!vector.isArray() || vector.isEmpty()) {
            throw new QueryTranslationException(
                    "Embedding API response did not contain a non-empty 'result[0].embedding_vector' array");
        }
        return vector;
    }
}
