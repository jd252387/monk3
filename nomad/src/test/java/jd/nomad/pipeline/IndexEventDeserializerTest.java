package jd.nomad.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Optional;
import jd.nomad.model.IndexEvent;
import org.junit.jupiter.api.Test;

class IndexEventDeserializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();
    private final IndexEventDeserializer deserializer = new IndexEventDeserializer(objectMapper, validator);

    @Test
    void returnsEventWhenPayloadIsValid() {
        String json =
                """
                {"primaryKey":"user-123","inlineDocument":{"name":"Test"}}
                """;

        Optional<IndexEvent> result = deserializer.deserialize(json);

        assertTrue(result.isPresent(), "Expected payload to deserialize successfully");
        IndexEvent event = result.orElseThrow();
        assertEquals("user-123", event.getPrimaryKey());
        assertEquals("{\"name\":\"Test\"}", event.getInlineDocumentBody());
    }

    @Test
    void capturesDatasourceKeyWhenProvided() {
        String json =
                """
                {"primaryKey":"user-123","datasourceKey":"source-999"}
                """;

        Optional<IndexEvent> result = deserializer.deserialize(json);

        assertTrue(result.isPresent(), "Expected payload to deserialize successfully");
        IndexEvent event = result.orElseThrow();
        assertEquals("user-123", event.getPrimaryKey());
        assertEquals("source-999", event.getDatasourceKey());
        assertEquals("source-999", event.getDatasourceKeyOrPrimary());
    }

    @Test
    void fallsBackToPrimaryKeyWhenDatasourceKeyMissing() {
        String json =
                """
                {"primaryKey":"user-123"}
                """;

        Optional<IndexEvent> result = deserializer.deserialize(json);

        assertTrue(result.isPresent(), "Expected payload to deserialize successfully");
        IndexEvent event = result.orElseThrow();
        assertEquals("user-123", event.getDatasourceKeyOrPrimary());
    }

    @Test
    void returnsEmptyWhenPrimaryKeyIsMissing() {
        String json = """
                {"inlineDocument":{"name":"Test"}}
                """;

        Optional<IndexEvent> result = deserializer.deserialize(json);

        assertTrue(result.isEmpty(), "Payload without primaryKey should be rejected");
    }

    @Test
    void returnsEmptyWhenPrimaryKeyIsBlank() {
        String json = """
                {"primaryKey":"   "}
                """;

        Optional<IndexEvent> result = deserializer.deserialize(json);

        assertTrue(result.isEmpty(), "Payload with blank primaryKey should be rejected");
    }
}
