package jd.nomad.model;

public record UpdateField(Object value, boolean isPartialUpdate, String operation) {

    // Constructor for regular fields (not partial updates)
    public UpdateField(Object value) {
        this(value, false, null);
    }

    // Constructor for partial update fields
    public UpdateField(Object value, String operation) {
        this(value, true, operation);
    }
}
