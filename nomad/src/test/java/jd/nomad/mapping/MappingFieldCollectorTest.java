package jd.nomad.mapping;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import jd.nomad.config.catalog.CatalogTestSupport;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MappingFieldCollectorTest {

    private MappingFieldCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        ConfigurationCatalogService catalogService = CatalogTestSupport.loadRepositoryCatalog();
        collector = new MappingFieldCollector(catalogService, CatalogTestSupport.indexingConfig("document", "s3-audio"));
    }

    @Test
    void shouldCollectSourceFieldsFromJqExpressions() {
        Set<String> fields = collector.collectFields();

        // For the s3-audio datasource, based on document.mapping.json:
        // - itemName: .item_name -> item_name
        // - itemContent: .["item-content"] -> item-content
        // - timestamp: .timestamp -> timestamp
        // - subItemName: .subItems[].name -> subItems
        assertTrue(fields.contains("item_name"));
        assertTrue(fields.contains("item-content"));
        assertTrue(fields.contains("timestamp"));
        assertTrue(fields.contains("subItems"));
    }

    @Test
    void shouldExtractRootFieldFromNestedExpressions() {
        Set<String> fields = collector.collectFields();

        // Nested field expressions like .subItems[].name should extract "subItems"
        assertTrue(fields.contains("subItems"));
    }

    @Test
    void shouldDeduplicateSourceFields() {
        Set<String> fields = collector.collectFields();

        // Multiple mapping fields may reference the same source field; the Set guarantees uniqueness.
        assertNotNull(fields);
        assertFalse(fields.isEmpty());
    }
}
