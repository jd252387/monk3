package com.monk3.search;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Arrays;

@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum SearchEngine {
    ELASTICSEARCH("elasticsearch", "minimum_should_match"),
    SOLR("solr", "mm");

    private final String pathName;
    private final String minimumShouldMatchProperty;

    public static SearchEngine fromPath(String pathName) {
        return Arrays.stream(values())
                .filter(searchEngine -> searchEngine.pathName.equalsIgnoreCase(pathName))
                .findFirst()
                .orElseThrow(() -> new QueryTranslationException(
                        "Unsupported search engine '" + pathName + "'. Supported values are: elasticsearch, solr"));
    }
}
