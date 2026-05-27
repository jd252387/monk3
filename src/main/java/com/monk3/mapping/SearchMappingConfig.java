package com.monk3.mapping;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "monk3.search")
public interface SearchMappingConfig {
    String materialTypeField();

    Map<String, Backend> backends();

    interface Backend {
        SearchBackendEngine engine();

        URI url();

        Optional<String> index();

        Optional<String> collection();

        List<String> materialTypes();

        @WithDefault("10")
        int defaultSize();
    }

    enum SearchBackendEngine {
        ELASTICSEARCH,
        SOLR
    }
}
