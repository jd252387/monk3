package com.monk3.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
public class JacksonConfiguration implements ObjectMapperCustomizer {
    @Override
    public void customize(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        objectMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
