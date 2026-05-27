package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import jd.nomad.mapping.BackendConfig;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface CatalogDatastore {

    CatalogSnapshot start(
            BiConsumer<String, JsonNode> mappingChangeListener,
            Consumer<Map<String, BackendConfig>> backendsChangeListener
    ) throws Exception;
}
