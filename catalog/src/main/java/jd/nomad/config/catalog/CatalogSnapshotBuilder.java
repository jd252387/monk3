package jd.nomad.config.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.mapping.DocumentMapping;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.MappedField;
import jd.nomad.mapping.MappingParseException;
import jd.nomad.mapping.SearchMapping;
import jd.nomad.mapping.SourceExpression;
import jd.nomad.mapping.VirtualDocumentMapping;
import jd.nomad.mapping.VirtualField;
import jd.nomad.mapping.VirtualMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class CatalogSnapshotBuilder {
    private static final Set<String> RESERVED_PROPERTIES = Set.of("$schema", "root");
    private static final Set<String> VIRTUAL_RESERVED_PROPERTIES = Set.of("$schema");
    private static final Set<String> DOCUMENT_RESERVED_PROPERTIES = Set.of("block_mask");

    public SearchMapping parseMapping(String materialType, JsonNode root) {
        ObjectNode mappingRoot = requireObject(root, "mapping for material type '" + materialType + "'");

        Map<String, DocumentMapping> documents = new LinkedHashMap<>();
        documents.put("root", parseDocument(
                "root",
                requireObject(mappingRoot.get("root"), "material type '" + materialType + "' #/root")));

        mappingRoot.properties().stream()
                .filter(entry -> !RESERVED_PROPERTIES.contains(entry.getKey()))
                .forEach(entry -> documents.put(entry.getKey(), parseDocument(
                        entry.getKey(),
                        requireObject(entry.getValue(), "material type '" + materialType + "' #/" + entry.getKey()))));

        return new SearchMapping(materialType, Map.copyOf(documents));
    }

    public VirtualMapping parseVirtualMapping(String materialType, JsonNode root) {
        ObjectNode mappingRoot = requireObject(root, "virtual mapping for material type '" + materialType + "'");

        Map<String, VirtualDocumentMapping> documents = new LinkedHashMap<>();
        mappingRoot.properties().stream()
                .filter(entry -> !VIRTUAL_RESERVED_PROPERTIES.contains(entry.getKey()))
                .forEach(entry -> documents.put(entry.getKey(), parseVirtualDocument(
                        entry.getKey(),
                        requireObject(entry.getValue(), "material type '" + materialType + "' #/" + entry.getKey()))));

        return new VirtualMapping(materialType, Map.copyOf(documents));
    }

    private VirtualDocumentMapping parseVirtualDocument(String documentName, ObjectNode documentNode) {
        Map<String, VirtualField> fields = new LinkedHashMap<>();
        documentNode.properties().forEach(entry -> fields.put(entry.getKey(), parseVirtualField(entry.getKey(), entry.getValue())));
        return new VirtualDocumentMapping(documentName, Map.copyOf(fields));
    }

    private VirtualField parseVirtualField(String logicalName, JsonNode fieldNode) {
        ObjectNode fieldObject = requireObject(fieldNode, "virtual field '" + logicalName + "'");
        FieldType type = FieldType.fromJson(requireText(fieldObject, "type", "virtual field '" + logicalName + "'"));
        if (type == FieldType.SUBDOCUMENT) {
            throw new MappingParseException("Virtual field '" + logicalName + "' may not use type 'subdocument'");
        }
        JsonNode expansion = fieldObject.get("expansion");
        if (!(expansion instanceof ObjectNode)) {
            throw new MappingParseException("Virtual field '" + logicalName + "' must declare an 'expansion' object");
        }
        return new VirtualField(logicalName, type, expansion);
    }

    private DocumentMapping parseDocument(String documentName, ObjectNode documentNode) {
        Map<String, MappedField> fields = new LinkedHashMap<>();
        documentNode.properties().stream()
                .filter(entry -> !DOCUMENT_RESERVED_PROPERTIES.contains(entry.getKey()))
                .forEach(entry -> fields.put(entry.getKey(), parseField(entry.getKey(), entry.getValue())));
        return new DocumentMapping(documentName, Map.copyOf(fields), Optional.ofNullable(optionalText(documentNode, "block_mask")));
    }

    private MappedField parseField(String logicalName, JsonNode fieldNode) {
        ObjectNode fieldObject = requireObject(fieldNode, "field '" + logicalName + "'");
        FieldType type = FieldType.fromJson(requireText(fieldObject, "type", "field '" + logicalName + "'"));
        String subdocumentType = optionalText(fieldObject, "subdocumentType");
        if (type == FieldType.SUBDOCUMENT && (subdocumentType == null || subdocumentType.isBlank())) {
            throw new MappingParseException("Subdocument field '" + logicalName + "' must declare subdocumentType");
        }

        Map<String, SourceExpression> sourcing = parseSourcing(fieldObject.get("sourcing"),
                "field '" + logicalName + "' sourcing");

        Map<String, SourceExpression> primaryKeySourcing = Map.of();
        Map<String, String> subdocumentPartialUpdate = Map.of();
        if (type == FieldType.SUBDOCUMENT) {
            primaryKeySourcing = parseSourcing(fieldObject.get("primaryKey"),
                    "subdocument field '" + logicalName + "' primaryKey");
            subdocumentPartialUpdate = parsePartialUpdate(fieldObject.get("partialUpdate"), logicalName);
        }

        return new MappedField(
                logicalName,
                type,
                subdocumentType,
                optionalText(fieldObject, "destinationField"),
                sourcing,
                primaryKeySourcing,
                subdocumentPartialUpdate);
    }

    /**
     * Parses a {@code datasource -> expression} sourcing map. Each value is either a bare string (treated as a
     * required {@code jq} expression) or an object declaring {@code jq} or {@code jsonPointer} (exactly one),
     * plus optional {@code partialUpdate} and {@code required}. Replicates nomad's dual-format sourcing parser.
     */
    private Map<String, SourceExpression> parseSourcing(JsonNode node, String location) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        ObjectNode sourcingObject = requireObject(node, location);
        Map<String, SourceExpression> byDatasource = new LinkedHashMap<>();
        sourcingObject.properties().forEach(entry ->
                byDatasource.put(entry.getKey(), parseSourceExpression(entry.getValue(),
                        location + " datasource '" + entry.getKey() + "'")));
        return Map.copyOf(byDatasource);
    }

    private SourceExpression parseSourceExpression(JsonNode valueNode, String location) {
        if (valueNode.isTextual()) {
            String jq = valueNode.asText();
            if (jq.isBlank()) {
                throw new MappingParseException("Empty jq expression at " + location);
            }
            return new SourceExpression(jq, null, null, true);
        }
        if (valueNode.isObject()) {
            ObjectNode object = (ObjectNode) valueNode;
            String jq = optionalText(object, "jq");
            String jsonPointer = optionalText(object, "jsonPointer");
            if ((jq == null || jq.isBlank()) == (jsonPointer == null || jsonPointer.isBlank())) {
                throw new MappingParseException(
                        "Exactly one of 'jq' or 'jsonPointer' must be set at " + location);
            }
            String partialUpdate = optionalText(object, "partialUpdate");
            JsonNode requiredNode = object.get("required");
            boolean required = requiredNode == null || requiredNode.isNull() || requiredNode.asBoolean(true);
            return new SourceExpression(jq, jsonPointer, partialUpdate, required);
        }
        throw new MappingParseException("Sourcing at " + location + " must be a string or an object");
    }

    private Map<String, String> parsePartialUpdate(JsonNode node, String logicalName) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        ObjectNode object = requireObject(node,
                "subdocument field '" + logicalName + "' partialUpdate");
        Map<String, String> byDatasource = new LinkedHashMap<>();
        object.properties().forEach(entry -> byDatasource.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(byDatasource);
    }

    /**
     * Parses the datasources document, accepting either a bare {@code { "<name>": {...} }} object or a wrapped
     * {@code { "datasources": { ... } }} object (mirroring the backends file convention). Each entry must
     * declare a {@code type}; the whole entry node is retained as the datasource configuration.
     */
    public Map<String, DatasourceDescriptor> parseDatasources(JsonNode root) {
        if (root == null || root.isNull()) {
            return Map.of();
        }
        ObjectNode rootObject = requireObject(root, "datasources document");
        JsonNode entries = rootObject.has("datasources") ? rootObject.get("datasources") : rootObject;
        ObjectNode entriesObject = requireObject(entries, "datasources document #/datasources");

        Map<String, DatasourceDescriptor> datasources = new LinkedHashMap<>();
        entriesObject.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode node = entry.getValue();
            if (node == null || node.isNull()) {
                return;
            }
            String type = optionalText(requireObject(node, "datasource '" + name + "'"), "type");
            if (type == null || type.isBlank()) {
                throw new MappingParseException("Datasource '" + name + "' must declare a type");
            }
            datasources.put(name, new DatasourceDescriptor(name, type.toLowerCase(), node));
        });
        return Map.copyOf(datasources);
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
