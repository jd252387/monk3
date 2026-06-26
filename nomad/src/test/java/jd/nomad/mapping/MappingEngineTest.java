package jd.nomad.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import jd.nomad.config.catalog.CatalogTestSupport;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.model.IndexCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MappingEngineTest {

    private MappingEngine mappingEngine;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        ConfigurationCatalogService catalogService = CatalogTestSupport.loadRepositoryCatalog();
        objectMapper = new ObjectMapper();
        JqEvaluationService jqEvaluator = new JqEvaluationService();
        JsonNodeConverter nodeConverter = new JsonNodeConverter(objectMapper);
        mappingEngine = new MappingEngine(
                catalogService,
                CatalogTestSupport.indexingConfig("document", "default-datasource"),
                jqEvaluator,
                nodeConverter);
    }

    @Test
    void shouldMapDocumentUsingCatalogConfiguration() throws Exception {
        JsonNode source = objectMapper.readTree(
                """
                {
                  "item-name": "Sample",
                  "item-content": "Document content",
                  "timestamp": "2024-03-01T00:00:00Z",
                  "subItems": [
                    {
                      "name": "child-1",
                      "itemName": "Child Name",
                      "itemContent": "Child Content"
                    }
                  ]
                }
                """);

        IndexCommand command = mappingEngine.map("root-1", null, source);

        assertEquals("root-1", command.getPrimaryKey());
        assertNull(command.getRootId());

        Map<String, jd.nomad.model.UpdateField> fields = command.getFields();
        assertEquals("Sample", fields.get("item_name").value());
        assertEquals("Document content", fields.get("item_content").value());
        assertEquals("2024-03-01T00:00:00Z", fields.get("timestamp").value());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subItems =
                (List<Map<String, Object>>) fields.get("subItems").value();
        assertNotNull(subItems);
        assertEquals(1, subItems.size());
        Map<String, Object> child = subItems.get(0);
        // Solr backend (solr-collection-text) uses item_id as the nested child id field.
        assertEquals("child-1", child.get("item_id"));
        assertEquals("Child Name", child.get("item_name"));
        assertEquals("Child Content", child.get("item_content"));
    }
}
