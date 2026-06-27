package jd.nomad.index.exception;

/**
 * Indicates that the target search engine is temporarily unavailable. This may be caused by
 * connectivity issues, cluster failures, or any other infrastructure problem that prevents
 * indexing requests from being processed.
 */
public class SearchEngineUnavailableException extends RuntimeException {

    public SearchEngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
