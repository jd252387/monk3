package com.monk.mapping;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Outbound timeouts for the per-backend search fan-out ({@code BackendHttpClient}). A dedicated
 * {@code @ConfigMapping} root so the {@code monk.search.backend.*} keys pass unknown-property
 * validation (the {@code monk} root is already claimed by other mappings).
 */
@ConfigMapping(prefix = "monk.search.backend")
public interface BackendHttpConfig {
    /** Max time to establish the connection to a search backend. */
    @WithDefault("5s")
    Duration connectTimeout();

    /** Max time for a single backend request/response. */
    @WithDefault("10s")
    Duration requestTimeout();
}
