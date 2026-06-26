package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.MappingParseException;
import jd.nomad.mapping.SearchMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSnapshotBuilderCapabilitiesTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CatalogSnapshotBuilder builder = new CatalogSnapshotBuilder();

    @Test
    void defaultsToSearchableAndFetchableButNotAggregatableOrSortable() throws Exception {
        MappedField field = parseField("""
                {
                  "root": {
                    "title": { "type": "freetext" }
                  }
                }
                """, "title");

        assertTrue(field.isSearchable());
        assertTrue(field.isFetchable());
        assertFalse(field.isAggregatable());
        assertFalse(field.isSortable());
    }

    @Test
    void parsesExplicitCapabilityFlags() throws Exception {
        MappedField field = parseField("""
                {
                  "root": {
                    "popularity": {
                      "type": "number",
                      "searchable": false,
                      "fetchable": false,
                      "aggregatable": true,
                      "sortable": true
                    }
                  }
                }
                """, "popularity");

        assertFalse(field.isSearchable());
        assertFalse(field.isFetchable());
        assertTrue(field.isAggregatable());
        assertTrue(field.isSortable());
    }

    @Test
    void rejectsNonBooleanCapabilityFlag() {
        assertThrows(MappingParseException.class, () -> parseField("""
                {
                  "root": {
                    "title": { "type": "freetext", "searchable": "yes" }
                  }
                }
                """, "title"));
    }

    private MappedField parseField(String json, String fieldName) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        SearchMapping mapping = builder.parseMapping("book", root);
        return mapping.document("root").orElseThrow().field(fieldName).orElseThrow();
    }
}
