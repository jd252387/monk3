package com.monk.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Sort direction for a {@link SortClause}, mirroring the Elasticsearch/Solr {@code asc}/{@code desc} order. */
public enum SortOrder {
    ASC("asc"),
    DESC("desc");

    private final String json;

    SortOrder(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
