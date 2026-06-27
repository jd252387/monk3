package jd.nomad.mapping;

import com.fasterxml.jackson.databind.JsonNode;

public record VirtualField(
        String logicalName,
        FieldType type,
        JsonNode expansion
) {
}
