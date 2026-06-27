package com.monk.mapping;

import io.smallrye.config.ConfigMapping;

import java.util.Map;

/**
 * Maps a capsule {@code endpointType} to its krembox fetch URL. Sourced from {@code monk.vapi}
 * in {@code application.yaml}; consumed when translating {@code capsule} text phrases to Solr.
 */
@ConfigMapping(prefix = "monk")
public interface VapiConfig {
    Map<String, String> vapi();
}
