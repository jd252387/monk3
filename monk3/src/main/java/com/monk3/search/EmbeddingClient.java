package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.mapping.EmbeddingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;

/**
 * Calls the external embedding API to turn free text into a dense vector. The API accepts
 * {@code {"texts":["<text>"]}} and returns {@code {"result":[{"embedding_vector":[...]}]}}. Used while
 * translating {@code knnFlat} queries; the returned vector is inlined into the engine query.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class EmbeddingClient {
    private final EmbeddingConfig config;
    private final JsonHttpClient jsonHttpClient;

    /** Returns the {@code embedding_vector} array node for the given text, or throws on any failure. */
    public JsonNode embed(String text) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putArray("texts").add(text);
        JsonNode response = jsonHttpClient.post(URI.create(config.url()), body, new JsonHttpClient.Errors() {
            @Override
            public RuntimeException status(int statusCode, String responseBody) {
                return new QueryTranslationException("Embedding API returned HTTP " + statusCode);
            }

            @Override
            public RuntimeException io(IOException cause) {
                return new QueryTranslationException("Embedding API could not be reached or returned invalid JSON", cause);
            }

            @Override
            public RuntimeException interrupted(InterruptedException cause) {
                return new QueryTranslationException("Embedding API request was interrupted", cause);
            }
        });
        return extractVector(response);
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
