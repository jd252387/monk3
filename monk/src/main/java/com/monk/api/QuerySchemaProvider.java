package com.monk.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.mapping.FieldTypeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.VirtualMapping;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the query DSL JSON Schema served by {@link QueryResource}. The bundled template
 * is enriched per request with {@code enum} constraints listing the field names declared in
 * the currently loaded mappings (physical and virtual), so consumers see which fields are
 * valid for the live configuration. Field enums are narrowed by the {@link FieldTypeConfig}
 * field-type rules: each query payload type and each aggregation type only lists the fields
 * whose mapping type it may target, the same rules the server enforces at translation time.
 * Built per request because the catalog hot-reloads.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class QuerySchemaProvider {
    private static final String SCHEMA_RESOURCE = "search-query-dsl.schema.json";
    private static final byte[] SCHEMA_TEMPLATE = loadSchemaTemplate();

    /** JSON Pointer to the recursive query node's {@code field} property. */
    private static final String QUERY_FIELD_POINTER = "/$defs/QueryNode/properties/field";
    /** JSON Pointer to the recursive query node's {@code allOf}, where per-type field constraints are appended. */
    private static final String QUERY_ALL_OF_POINTER = "/$defs/QueryNode/allOf";
    /** JSON Pointer to the top-level result projection items. */
    private static final String PROJECTION_POINTER = "/properties/fields/items";

    private final ConfigurationCatalogService catalogService;
    private final FieldTypeConfig fieldTypeConfig;
    private final ObjectMapper objectMapper;

    /** The DSL schema with field {@code enum}s reflecting the currently loaded mappings and field-type rules. */
    public byte[] schema() {
        JsonNode root;
        try {
            root = objectMapper.readTree(SCHEMA_TEMPLATE);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to parse " + SCHEMA_RESOURCE, exception);
        }

        Map<FieldType, TreeSet<String>> physicalByType = new EnumMap<>(FieldType.class);
        TreeSet<String> virtualNames = new TreeSet<>();
        collectFields(physicalByType, virtualNames);

        TreeSet<String> allNames = new TreeSet<>(virtualNames);
        physicalByType.values().forEach(allNames::addAll);

        // The query node field is also a boolean/nested node when empty or a subdocument field name,
        // so the empty string must remain valid alongside the mapped field names.
        List<String> queryFieldEnum = new ArrayList<>();
        queryFieldEnum.add("");
        queryFieldEnum.addAll(allNames);
        setEnum(root, QUERY_FIELD_POINTER, queryFieldEnum);
        setEnum(root, PROJECTION_POINTER, List.copyOf(allNames));

        // Per query payload type: narrow the field to the physical fields of the allowed types plus all
        // virtual fields (a virtual field's payload compatibility is enforced at expansion time, not here).
        fieldTypeConfig.query().fieldTypes().forEach((queryType, types) -> {
            TreeSet<String> allowed = namesOfTypes(physicalByType, types);
            allowed.addAll(virtualNames);
            addQueryTypeConstraint(root, queryType, allowed);
        });

        // Per aggregation type: restrict args.field to the physical fields of the allowed types (aggregations
        // reject virtual fields, so they are intentionally excluded).
        fieldTypeConfig.aggregation().fieldTypes().forEach((aggType, types) ->
                setEnum(root, aggregationFieldPointer(aggType), List.copyOf(namesOfTypes(physicalByType, types))));

        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to serialize query DSL schema", exception);
        }
    }

    /** Populates {@code physicalByType} (physical fields grouped by mapping type) and {@code virtualNames}. */
    private void collectFields(Map<FieldType, TreeSet<String>> physicalByType, TreeSet<String> virtualNames) {
        for (SearchMapping mapping : catalogService.allMappings()) {
            mapping.documents().values().forEach(document -> document.fields().values().forEach(field ->
                    physicalByType.computeIfAbsent(field.type(), t -> new TreeSet<>()).add(field.logicalName())));
        }
        for (VirtualMapping mapping : catalogService.allVirtualMappings()) {
            mapping.documents().values().forEach(document -> virtualNames.addAll(document.fields().keySet()));
        }
    }

    /** Union of the physical field names across the given types. */
    private static TreeSet<String> namesOfTypes(Map<FieldType, TreeSet<String>> physicalByType, List<FieldType> types) {
        TreeSet<String> names = new TreeSet<>();
        for (FieldType type : types) {
            names.addAll(physicalByType.getOrDefault(type, new TreeSet<>()));
        }
        return names;
    }

    /**
     * Appends an {@code if/then} clause to {@code QueryNode.allOf} constraining a leaf node's {@code field}
     * to {@code allowed} whenever its {@code data} is an object with {@code type == queryType}. The
     * {@code type: object} guard keeps the clause from matching boolean nodes (whose {@code data} is an array).
     * Skipped when {@code allowed} is empty (an empty enum would reject an otherwise-valid query shape).
     */
    private void addQueryTypeConstraint(JsonNode root, String queryType, Set<String> allowed) {
        if (allowed.isEmpty()) {
            return;
        }
        JsonNode allOf = root.at(QUERY_ALL_OF_POINTER);
        if (!(allOf instanceof ArrayNode allOfArray)) {
            throw new IllegalStateException("Expected an array at " + QUERY_ALL_OF_POINTER + " in " + SCHEMA_RESOURCE);
        }
        ObjectNode clause = allOfArray.addObject();

        ObjectNode ifNode = clause.putObject("if");
        ifNode.putArray("required").add("data");
        ObjectNode dataSchema = ifNode.putObject("properties").putObject("data");
        dataSchema.put("type", "object");
        dataSchema.putArray("required").add("type");
        dataSchema.putObject("properties").putObject("type").put("const", queryType);

        ArrayNode enumNode = clause.putObject("then").putObject("properties").putObject("field").putArray("enum");
        allowed.forEach(enumNode::add);
    }

    /** JSON Pointer to an aggregation def's {@code args.field}, e.g. {@code terms} → {@code TermsAggregation}. */
    private static String aggregationFieldPointer(String aggType) {
        String defName = Character.toUpperCase(aggType.charAt(0)) + aggType.substring(1) + "Aggregation";
        return "/$defs/" + defName + "/properties/args/properties/field";
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
