package com.monk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Builds the query DSL JSON Schema served by {@link QueryResource}. The bundled template
 * is enriched per request with {@code enum} constraints listing the field names declared in
 * the currently loaded mappings (physical and virtual), so consumers see which fields are
 * valid for the live configuration. Built per request because the catalog hot-reloads.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class QuerySchemaProvider {
    private static final String SCHEMA_RESOURCE = "search-query-dsl.schema.json";
    private static final byte[] SCHEMA_TEMPLATE = loadSchemaTemplate();

    /** JSON Pointer to the recursive query node's {@code field} property. */
    private static final String QUERY_FIELD_POINTER = "/$defs/QueryNode/properties/field";
    /** JSON Pointer to the top-level result projection items. */
    private static final String PROJECTION_POINTER = "/properties/fields/items";
    /** Aggregation definitions that take an {@code args.field}. {@code FilterAggregation} takes a query, not a field. */
    private static final List<String> FIELD_AGGREGATIONS = List.of(
            "TermsAggregation",
            "UniqueAggregation",
            "RangeAggregation",
            "SubfacetsAggregation",
            "SumAggregation",
            "AvgAggregation",
            "MinAggregation",
            "MaxAggregation");

    private final ConfigurationCatalogService catalogService;
    private final ObjectMapper objectMapper;

    /** The DSL schema with field {@code enum}s reflecting the currently loaded mappings. */
    public byte[] schema() {
        JsonNode root;
        try {
            root = objectMapper.readTree(SCHEMA_TEMPLATE);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to parse " + SCHEMA_RESOURCE, exception);
        }

        List<String> fieldNames = collectFieldNames();

        // The query node field is also a boolean/nested node when empty or a subdocument field name,
        // so the empty string must remain valid alongside the mapped field names.
        List<String> queryFieldEnum = new ArrayList<>();
        queryFieldEnum.add("");
        queryFieldEnum.addAll(fieldNames);

        setEnum(root, QUERY_FIELD_POINTER, queryFieldEnum);
        setEnum(root, PROJECTION_POINTER, fieldNames);
        for (String aggregation : FIELD_AGGREGATIONS) {
            setEnum(root, "/$defs/" + aggregation + "/properties/args/properties/field", fieldNames);
        }

        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to serialize query DSL schema", exception);
        }
    }

    /** Distinct, sorted logical field names across every document block of every physical and virtual mapping. */
    private List<String> collectFieldNames() {
        TreeSet<String> names = new TreeSet<>();
        for (SearchMapping mapping : catalogService.allMappings()) {
            mapping.documents().values().forEach(document -> names.addAll(document.fields().keySet()));
        }
        for (VirtualMapping mapping : catalogService.allVirtualMappings()) {
            mapping.documents().values().forEach(document -> names.addAll(document.fields().keySet()));
        }
        return List.copyOf(names);
    }

    /**
     * Replaces the node at {@code pointer} with an {@code enum} of {@code values}. Skipped when empty:
     * an empty enum would reject every value, so a configuration with no mappings keeps the plain string.
     */
    private void setEnum(JsonNode root, String pointer, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonNode node = root.at(pointer);
        if (!(node instanceof ObjectNode target)) {
            throw new IllegalStateException("Expected an object at " + pointer + " in " + SCHEMA_RESOURCE);
        }
        ArrayNode enumNode = target.putArray("enum");
        values.forEach(enumNode::add);
    }

    private static byte[] loadSchemaTemplate() {
        try (InputStream inputStream = QuerySchemaProvider.class
                .getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " was not found on the classpath");
            }
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + SCHEMA_RESOURCE, exception);
        }
    }
}
