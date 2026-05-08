package com.monk3.mapping;

import io.smallrye.config.ConfigMapping;

import java.util.Map;

@ConfigMapping(prefix = "monk3.search")
public interface SearchMappingConfig {
    String materialTypeField();

    Map<String, String> materialTypeMappings();

    Solr solr();

    interface Solr {
        String parentBlockMask();
    }
}
