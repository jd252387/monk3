package com.monk3.mapping;

import io.smallrye.config.ConfigMapping;

/**
 * Configuration for the external embedding API used to translate {@code knnFlat} queries into vectors.
 * Sourced from {@code monk3.embedding} in {@code application.yaml}.
 */
@ConfigMapping(prefix = "monk3.embedding")
public interface EmbeddingConfig {
    /** Endpoint that accepts {@code {"texts":[...]}} and returns {@code {"result":[{"embedding_vector":[...]}]}}. */
    String url();
}
