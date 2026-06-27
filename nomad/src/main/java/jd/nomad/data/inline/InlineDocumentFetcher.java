package jd.nomad.data.inline;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;

import java.util.Set;

import jd.nomad.data.DataFetcher;
import jd.nomad.model.IndexEvent;

public class InlineDocumentFetcher implements DataFetcher {

    @Override
    public Uni<JsonNode> fetch(IndexEvent event, Set<String> fields) {
        JsonNode inlineDocument = event.getInlineDocument();
        if (inlineDocument == null || inlineDocument.isNull()) {
            return Uni.createFrom()
                    .failure(new IllegalArgumentException(
                            "Inline document payload is required when no external data source is configured"));
        }
        return Uni.createFrom().item(inlineDocument);
    }
}
