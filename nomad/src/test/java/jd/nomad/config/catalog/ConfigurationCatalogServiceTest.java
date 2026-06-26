package jd.nomad.config.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.SearchMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared {@code :catalog} configuration loads the indexer-oriented additions (datasources,
 * per-field sourcing/primaryKey, backend connection details) from the repository {@code config/} files.
 */
class ConfigurationCatalogServiceTest {

    private ConfigurationCatalogService service;

    @BeforeEach
    void setUp() throws Exception {
        service = CatalogTestSupport.loadRepositoryCatalog();
    }

    @Test
    void shouldLoadDatasourceByName() {
        DatasourceDescriptor descriptor = service.datasource("s3-audio");
        assertNotNull(descriptor);
        assertEquals("s3", descriptor.type());
    }

    @Test
    void shouldExposeBackendConnectionDetails() {
        BackendConfig solr = service.backendConfig("solr-collection-text");
        assertEquals("localhost:2181", solr.zk());
        assertEquals("/solr", solr.chroot());
        assertEquals("text", solr.collection());

        BackendConfig elastic = service.backendConfig("elastic-index-text");
        assertFalse(elastic.hosts().isEmpty());
        assertEquals("index-text", elastic.index());
    }

    @Test
    void shouldLoadMappingWithSourcing() {
        SearchMapping mapping = service.mappingForMaterialType("document");

        MappedField itemName = mapping.root().field("itemName").orElseThrow();
        assertEquals("item_name", itemName.searchField());
        assertTrue(itemName.sourcingFor("default-datasource").isPresent());

        MappedField subItems = mapping.root().field("subItems").orElseThrow();
        assertTrue(subItems.isSubdocument());
        assertTrue(subItems.primaryKeyFor("default-datasource").isPresent());
        assertEquals("set", subItems.subdocumentPartialUpdateFor("default-datasource").orElseThrow());
    }
}
