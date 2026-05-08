package com.monk3.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.QueryTranslationException;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class MappingRepository {
    private static final Set<String> RESERVED_PROPERTIES = Set.of("$schema", "root", "primaryKey");

    private final Map<String, SearchMapping> mappingsByMaterialType;

    public MappingRepository(ObjectMapper objectMapper, SearchMappingConfig config) {
        Map<String, SearchMapping> loadedMappings = new LinkedHashMap<>();
        config.materialTypeMappings()
                .forEach((materialType, resourcePath) ->
                        loadedMappings.put(materialType, loadMapping(objectMapper, materialType, resourcePath)));
        this.mappingsByMaterialType = Map.copyOf(loadedMappings);
    }

    public SearchMapping mappingForMaterialType(String materialType) {
        SearchMapping mapping = mappingsByMaterialType.get(materialType);
        if (mapping == null) {
            throw new QueryTranslationException("No mapping is configured for material type '" + materialType + "'");
        }
        return mapping;
    }

    private SearchMapping loadMapping(ObjectMapper objectMapper, String materialType, String resourcePath) {
        try (InputStream inputStream = contextClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new QueryTranslationException(
                        "Mapping resource '" + resourcePath + "' configured for material type '" + materialType + "' was not found");
            }
            ObjectNode root = requireObject(objectMapper.readTree(inputStream), resourcePath);
            String primaryKey = requireText(root, "primaryKey", resourcePath);
            Map<String, DocumentMapping> documents = new LinkedHashMap<>();
            documents.put("root", parseDocument("root", requireObject(root.get("root"), resourcePath + "#/root")));

            Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!RESERVED_PROPERTIES.contains(entry.getKey())) {
                    documents.put(entry.getKey(), parseDocument(
                            entry.getKey(),
                            requireObject(entry.getValue(), resourcePath + "#/" + entry.getKey())));
                }
            }

            return new SearchMapping(materialType, primaryKey, Map.copyOf(documents));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load mapping resource '" + resourcePath + "'", exception);
        }
    }

    private DocumentMapping parseDocument(String documentName, ObjectNode documentNode) {
        Map<String, MappedField> fields = new LinkedHashMap<>();
        documentNode.properties().forEach(entry -> fields.put(entry.getKey(), parseField(entry.getKey(), entry.getValue())));
        return new DocumentMapping(documentName, Map.copyOf(fields));
    }

    private MappedField parseField(String logicalName, JsonNode fieldNode) {
        ObjectNode fieldObject = requireObject(fieldNode, "field '" + logicalName + "'");
        FieldType type = FieldType.fromJson(requireText(fieldObject, "type", "field '" + logicalName + "'"));
        String subdocumentType = optionalText(fieldObject, "subdocumentType");
        if (type == FieldType.SUBDOCUMENT && (subdocumentType == null || subdocumentType.isBlank())) {
            throw new QueryTranslationException("Subdocument field '" + logicalName + "' must declare subdocumentType");
        }
        return new MappedField(
                logicalName,
                type,
                subdocumentType,
                optionalText(fieldObject, "destinationField"),
                optionalText(fieldObject, "sourceField"));
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return classLoader == null ? MappingRepository.class.getClassLoader() : classLoader;
    }

    private static ObjectNode requireObject(JsonNode node, String location) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new QueryTranslationException("Expected JSON object at " + location);
    }

    private static String requireText(ObjectNode node, String property, String location) {
        String value = optionalText(node, property);
        if (value == null || value.isBlank()) {
            throw new QueryTranslationException("Expected non-empty string property '" + property + "' at " + location);
        }
        return value;
    }

    private static String optionalText(ObjectNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? null : value.asText();
    }
}
