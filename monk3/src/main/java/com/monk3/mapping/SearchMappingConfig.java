package com.monk3.mapping;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "monk3.search")
public interface SearchMappingConfig {
    String materialTypeField();
}
