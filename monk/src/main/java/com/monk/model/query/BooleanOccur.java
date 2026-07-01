package com.monk.model.query;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a clause within a {@link BooleanQueryData} combines with its siblings, mirroring the
 * Elasticsearch/Solr {@code bool} buckets.
 */
public enum BooleanOccur {
    SHOULD("should"),
    MUST("must"),
    MUST_NOT("mustNot");

    private final String json;

    BooleanOccur(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
