package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.MappingParseException;
import jd.nomad.mapping.SearchMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CatalogSnapshotBuilder {
    private static final Set<String> RESERVED_PROPERTIES = Set.of("$schema", "root", "primaryKey");

    public SearchMapping parseMapping(String materialType, JsonNode root) {
        ObjectNode mappingRoot = requireObject(root, "mapping for material type '" + materialType + "'");
        String primaryKey = requireText(mappingRoot, "primaryKey", "material type '" + materialType + "'");

        Map<String, DocumentMapping> documents = new LinkedHashMap<>();
        documents.put("root", parseDocument(
                "root",
                requireObject(mappingRoot.get("root"), "material type '" + materialType + "' #/root")));

        mappingRoot.properties().stream()
                .filter(entry -> !RESERVED_PROPERTIES.contains(entry.getKey()))
                .forEach(entry -> documents.put(entry.getKey(), parseDocument(
                        entry.getKey(),
                        requireObject(entry.getValue(), "material type '" + materialType + "' #/" + entry.getKey()))));

        return new SearchMapping(materialType, primaryKey, Map.copyOf(documents));
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
            throw new MappingParseException("Subdocument field '" + logicalName + "' must declare subdocumentType");
        }
        return new MappedField(
                logicalName,
                type,
                subdocumentType,
                optionalText(fieldObject, "destinationField"));
    }

    private static ObjectNode requireObject(JsonNode node, String location) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new MappingParseException("Expected JSON object at " + location);
    }

    private static String requireText(ObjectNode node, String property, String location) {
        String value = optionalText(node, property);
        if (value == null || value.isBlank()) {
            throw new MappingParseException("Expected non-empty string property '" + property + "' at " + location);
        }
        return value;
    }

    private static String optionalText(ObjectNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? null : value.asText();
    }
}
