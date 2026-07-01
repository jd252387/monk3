package com.monk.mapping;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import jd.nomad.mapping.FieldType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for which mapping {@link FieldType}s each query payload and each
 * aggregation may target. Sourced from {@code monk.query.field-types} and
 * {@code monk.aggregation.field-types} in {@code application.yaml}; consumed by query/aggregation
 * validation ({@code QueryParseContext} / {@code AggregationContext}) and by schema generation
 * ({@code QuerySchemaProvider}). Query and aggregation are separate sections because a key such as
 * {@code range} means different things in each (query range allows datetime; range aggregation does not).
 */
@ConfigMapping(prefix = "monk")
public interface FieldTypeConfig {
    Query query();

    Aggregation aggregation();

    interface Query {
        /** Query payload {@code type()} discriminator to the field types it may target. */
        @WithName("field-types")
        Map<String, List<FieldType>> fieldTypes();
    }

    interface Aggregation {
        /** Aggregation {@code aggType()} discriminator to the field types it may target. */
        @WithName("field-types")
        Map<String, List<FieldType>> fieldTypes();
    }

    /** Field types the given query payload type may target; fails loud if the type is unconfigured. */
    default Set<FieldType> forQuery(String queryType) {
        return require(query().fieldTypes(), queryType, "query");
    }

    /** Field types the given aggregation type may target; fails loud if the type is unconfigured. */
    default Set<FieldType> forAggregation(String aggType) {
        return require(aggregation().fieldTypes(), aggType, "aggregation");
    }

    private static Set<FieldType> require(Map<String, List<FieldType>> configured, String type, String kind) {
        List<FieldType> types = configured.get(type);
        if (types == null) {
            throw new IllegalStateException("No " + kind + " field-types configured for type '" + type + "'");
        }
        return Set.copyOf(types);
    }
}
