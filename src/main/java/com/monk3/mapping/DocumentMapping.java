package com.monk3.mapping;

import java.util.Map;
import java.util.Optional;

public record DocumentMapping(
        String name,
        Map<String, MappedField> fields
) {
    public Optional<MappedField> field(String logicalName) {
        return Optional.ofNullable(fields.get(logicalName));
    }
}
