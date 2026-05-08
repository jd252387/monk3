package com.monk3.search;

public class SearchExecutionException extends RuntimeException {
    public SearchExecutionException(String message) {
        super(message);
    }

    public SearchExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
