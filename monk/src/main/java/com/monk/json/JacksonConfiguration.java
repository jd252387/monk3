package com.monk.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.monk.model.agg.Aggregation;
import com.monk.model.query.QueryNode;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
public class JacksonConfiguration implements ObjectMapperCustomizer {
    private final QueryNodeDeserializer queryNodeDeserializer;
    private final AggregationDeserializer aggregationDeserializer;

    JacksonConfiguration(QueryNodeDeserializer queryNodeDeserializer, AggregationDeserializer aggregationDeserializer) {
        this.queryNodeDeserializer = queryNodeDeserializer;
        this.aggregationDeserializer = aggregationDeserializer;
    }

    @Override
    public void customize(ObjectMapper objectMapper) {
        objectMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.registerModule(new SimpleModule()
                .addDeserializer(QueryNode.class, queryNodeDeserializer)
                .addDeserializer(Aggregation.class, aggregationDeserializer));
    }
}
