package jd.nomad.mapping;

import java.util.Map;
import java.util.Optional;

public record SearchMapping(
        String materialType,
        String primaryKey,
        Map<String, DocumentMapping> documents
) {
    private static final String ROOT_DOCUMENT = "root";

    public DocumentMapping root() {
        return document(ROOT_DOCUMENT)
                .orElseThrow(() -> new IllegalStateException("Mapping does not contain a root document"));
    }

    public Optional<DocumentMapping> document(String name) {
        return Optional.ofNullable(documents.get(name));
    }
}
