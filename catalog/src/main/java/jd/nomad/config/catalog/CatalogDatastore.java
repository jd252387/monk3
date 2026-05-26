package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.BiConsumer;

public interface CatalogDatastore {

    /**
     * @param mappingChangeListener callback invoked with (materialType, updated mapping JSON)
     *                              when the underlying datastore reports a change
     * @return the initial CatalogSnapshot
     */
    CatalogSnapshot start(BiConsumer<String, JsonNode> mappingChangeListener) throws Exception;
}
