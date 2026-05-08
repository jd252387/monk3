package com.monk3.search;

public class UnsupportedQueryDataException extends QueryTranslationException {
    public UnsupportedQueryDataException(String queryDataType, String targetSearchEngine) {
        super(queryDataType + " is not supported for " + targetSearchEngine);
    }
}
