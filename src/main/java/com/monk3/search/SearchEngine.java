package com.monk3.search;

import java.util.Arrays;

public enum SearchEngine {
    ELASTICSEARCH("elasticsearch"),
    SOLR("solr");

    private final String pathName;

    SearchEngine(String pathName) {
        this.pathName = pathName;
    }

    public static SearchEngine fromPath(String pathName) {
        return Arrays.stream(values())
                .filter(searchEngine -> searchEngine.pathName.equalsIgnoreCase(pathName))
                .findFirst()
                .orElseThrow(() -> new QueryTranslationException(
                        "Unsupported search engine '" + pathName + "'. Supported values are: elasticsearch, solr"));
    }

    public String pathName() {
        return pathName;
    }
}
