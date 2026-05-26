package jd.nomad.mapping;

public class MappingParseException extends RuntimeException {
    public MappingParseException(String message) {
        super(message);
    }

    public MappingParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
