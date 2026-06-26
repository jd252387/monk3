package jd.nomad.index.exception;

/**
 * Indicates that the search engine rejected an indexing request because of an issue with the
 * payload itself (for example invalid field types or ingest pipeline failures).
 */
public class SearchEngineRequestException extends RuntimeException {

    public SearchEngineRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
