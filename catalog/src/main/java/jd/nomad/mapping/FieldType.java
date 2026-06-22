package jd.nomad.mapping;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;

public enum FieldType {
    STRING,
    FREETEXT,
    DATETIME,
    NUMBER,
    BOOLEAN,
    SUBDOCUMENT,
    PREDICATE,
    SUBQUERY;

    @JsonCreator
    public static FieldType fromJson(String value) {
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported mapping field type: " + value));
    }
}
