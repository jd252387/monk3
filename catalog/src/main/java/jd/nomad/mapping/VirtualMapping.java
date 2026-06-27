package jd.nomad.mapping;

import java.util.Map;
import java.util.Optional;

public record VirtualMapping(
        String materialType,
        Map<String, VirtualDocumentMapping> documents
) {
    private static final String ROOT_DOCUMENT = "root";

    public VirtualDocumentMapping root() {
        return document(ROOT_DOCUMENT)
                .orElseThrow(() -> new IllegalStateException("Virtual mapping does not contain a root document"));
    }

    public Optional<VirtualDocumentMapping> document(String name) {
        return Optional.ofNullable(documents.get(name));
    }
}
