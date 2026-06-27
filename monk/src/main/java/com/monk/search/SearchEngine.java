package com.monk.search;

import com.fasterxml.jackson.core.JsonPointer;
import jd.nomad.mapping.BackendEngine;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum SearchEngine {
    ELASTICSEARCH("minimum_should_match", JsonPointer.compile("/hits/hits"), "_score", "aggs", "aggregations", "size"),
    SOLR("mm", JsonPointer.compile("/response/docs"), "score", "facet", "facets", "limit");

    private final String minimumShouldMatchProperty;
    private final JsonPointer resultsPath;
    private final String scoreField;
    private final String aggregationsRequestProperty;
    private final String aggregationsResponseProperty;
    private final String sizeProperty;

    public static SearchEngine of(BackendEngine engine) {
        return valueOf(engine.name());
    }
}
