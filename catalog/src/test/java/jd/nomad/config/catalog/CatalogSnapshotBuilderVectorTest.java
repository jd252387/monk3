package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.MappingParseException;
import jd.nomad.mapping.SearchMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSnapshotBuilderVectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CatalogSnapshotBuilder builder = new CatalogSnapshotBuilder();

    @Test
    void parsesVectorFieldAndExpandsDestinationFieldOverInclusiveRange() throws Exception {
        SearchMapping mapping = parse("""
                {
                  "root": {
                    "embedding": { "type": "vector", "destinationField": "vector_%i", "start": 1, "end": 3 }
                  }
                }
                """);

        MappedField field = mapping.document("root").orElseThrow().field("embedding").orElseThrow();
        assertEquals(FieldType.VECTOR, field.type());
        assertTrue(field.isVector());
        assertEquals(1, field.vectorSpec().start());
        assertEquals(3, field.vectorSpec().end());
        assertEquals(List.of("vector_1", "vector_2", "vector_3"), field.vectorFields());
    }

    @Test
    void rejectsVectorFieldWithoutPlaceholderInDestinationField() {
        assertThrows(MappingParseException.class, () -> parse("""
                {
                  "root": {
                    "embedding": { "type": "vector", "destinationField": "vector", "start": 1, "end": 3 }
                  }
                }
                """));
    }

    @Test
    void rejectsVectorFieldMissingRangeBound() {
        assertThrows(MappingParseException.class, () -> parse("""
                {
                  "root": {
                    "embedding": { "type": "vector", "destinationField": "vector_%i", "start": 1 }
                  }
                }
                """));
    }

    private SearchMapping parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        return builder.parseMapping("book", root);
    }
}
