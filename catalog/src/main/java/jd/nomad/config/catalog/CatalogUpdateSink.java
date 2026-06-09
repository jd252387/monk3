package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import jd.nomad.mapping.BackendConfig;

import java.util.Map;

/**
 * Receives configuration updates pushed by a {@link CatalogDatastore} while the application is running.
 *
 * <p>Datastores may either apply fine-grained updates ({@link #updateMapping}, {@link #updateBackends})
 * or replace the whole {@link CatalogSnapshot} atomically ({@link #replaceSnapshot}).
 */
public interface CatalogUpdateSink {

    void updateMapping(String materialType, JsonNode node);

    void updateBackends(Map<String, BackendConfig> backends);

    void replaceSnapshot(CatalogSnapshot snapshot);
}
