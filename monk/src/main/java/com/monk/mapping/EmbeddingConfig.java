package com.monk.mapping;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Configuration for the external embedding API used to translate {@code knnFlat} queries into vectors.
 * Sourced from {@code monk.embedding} in {@code application.yaml}.
 */
@ConfigMapping(prefix = "monk.embedding")
public interface EmbeddingConfig {
    /** Endpoint that accepts {@code {"texts":[...]}} and returns {@code {"result":[{"embedding_vector":[...]}]}}. */
    String url();

    /** Max time to establish the connection to the embedding API. */
    @WithDefault("5s")
    Duration connectTimeout();

    /** Max time for a single embedding request/response. */
    @WithDefault("10s")
    Duration requestTimeout();
}
