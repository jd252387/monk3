package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * A named datasource declaration from the catalog. {@code configuration} carries the raw, datasource-specific
 * JSON (e.g. the {@code s3}/{@code rest-api}/{@code mongodb} block) which each indexer fetcher factory parses
 * on its own. The query side (monk) does not use datasources.
 */
public record DatasourceDescriptor(String name, String type, JsonNode configuration) {

    public JsonNode configuration() {
        return configuration == null ? NullNode.getInstance() : configuration;
    }
}
