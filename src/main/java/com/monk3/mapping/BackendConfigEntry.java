package com.monk3.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.Optional;

public class BackendConfigEntry implements SearchMappingConfig.Backend {
    @JsonProperty private SearchMappingConfig.SearchBackendEngine engine;
    @JsonProperty private URI url;
    @JsonProperty private String index;
    @JsonProperty private String collection;
    @JsonProperty private int defaultSize = 10;

    @Override public SearchMappingConfig.SearchBackendEngine engine() { return engine; }
    @Override public URI url() { return url; }
    @Override public Optional<String> index() { return Optional.ofNullable(index); }
    @Override public Optional<String> collection() { return Optional.ofNullable(collection); }
    @Override public int defaultSize() { return defaultSize; }
}
