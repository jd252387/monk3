package jd.nomad.mapping;

import java.util.Map;
import java.util.Optional;

public record VirtualDocumentMapping(
        String name,
        Map<String, VirtualField> fields
) {
    public Optional<VirtualField> field(String logicalName) {
        return Optional.ofNullable(fields.get(logicalName));
    }
}
