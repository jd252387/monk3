package jd.nomad.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jd.nomad.config.IndexingConfig;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.model.IndexCommand;
import jd.nomad.model.UpdateField;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates a fetched source document into an {@link IndexCommand} by walking the catalog {@link SearchMapping}
 * for the configured material type. Leaf values are extracted per-datasource via {@link SourceExpression}s, and
 * subdocument fields are expanded into per-child documents whose id field name depends on the active backend
 * engine ({@code item_id} for Solr, {@code _id} otherwise).
 */
@Slf4j
@ApplicationScoped
public class MappingEngine {

    private final ConfigurationCatalogService catalogService;
    private final IndexingConfig indexerConfig;
    private final String materialType;
    private final String datasourceName;
    private final JqEvaluationService jqEvaluator;
    private final JsonNodeConverter nodeConverter;
    private final List<String> partialUpdateHierarchy;

    @Inject
    public MappingEngine(
            ConfigurationCatalogService catalogService,
            IndexingConfig indexerConfig,
            JqEvaluationService jqEvaluator,
            JsonNodeConverter nodeConverter) {
        this.catalogService = catalogService;
        this.indexerConfig = indexerConfig;
        this.materialType = indexerConfig.materialType();
        this.datasourceName = indexerConfig.dataSource();
        this.jqEvaluator = jqEvaluator;
        this.nodeConverter = nodeConverter;
        this.partialUpdateHierarchy = indexerConfig
                .partialUpdateHierarchy()
                .map(IndexingConfig.PartialUpdateHierarchy::path)
                .map(List::copyOf)
                .orElse(List.of());
    }

    public IndexCommand map(String primaryKey, String rootId, JsonNode fetchedDocument) {
        if (fetchedDocument == null || fetchedDocument.isMissingNode() || fetchedDocument.isNull()) {
            throw new MappingException("Fetched document is empty for primary key " + primaryKey);
        }
        SearchMapping mapping = catalogService.mappingForMaterialType(materialType);
        BackendEngine engine = activeBackend().engine();

        Map<String, UpdateField> fields = new LinkedHashMap<>();

        if (!partialUpdateHierarchy.isEmpty()) {
            if (rootId == null || rootId.isBlank()) {
                String message = "Missing _root_ identifier for nested partial update targeting path "
                        + String.join(".", partialUpdateHierarchy);
                log.error(message);
                throw new MappingException(message);
            }

            MappedField targetField = resolveTargetField(mapping, partialUpdateHierarchy);
            FieldValue fieldValue = processField(
                            mapping, engine, fetchedDocument, targetField, targetField.logicalName())
                    .orElseThrow(() -> {
                        String message = "No values produced for nested field '"
                                + String.join(".", partialUpdateHierarchy) + "'";
                        log.error(message);
                        return new MappingException(message);
                    });
            fields.put(fieldValue.target(), toUpdateField(fieldValue));

            return IndexCommand.builder().primaryKey(primaryKey).rootId(rootId).fields(fields).build();
        }

        for (MappedField field : mapping.root().fields().values()) {
            processField(mapping, engine, fetchedDocument, field, field.logicalName())
                    .ifPresent(entry -> fields.put(entry.target(), toUpdateField(entry)));
        }

        return IndexCommand.builder().primaryKey(primaryKey).rootId(rootId).fields(fields).build();
    }

    private BackendConfig activeBackend() {
        String backendName =
                indexerConfig.backend().orElseGet(() -> catalogService.backendForMaterialType(materialType));
        return catalogService.backendConfig(backendName);
    }

    private static UpdateField toUpdateField(FieldValue value) {
        return value.operation() != null
                ? new UpdateField(value.value(), value.operation())
                : new UpdateField(value.value());
    }

    private Optional<FieldValue> processField(
            SearchMapping mapping, BackendEngine engine, JsonNode rootContext, MappedField field, String fieldPath) {
        if (field.isSubdocument()) {
            return processSubdocumentField(mapping, engine, rootContext, field, fieldPath);
        }

        Optional<SourceExpression> sourcingOpt = field.sourcingFor(datasourceName);
        if (sourcingOpt.isEmpty()) {
            // Field carries no sourcing for the active datasource: it is simply not indexed here.
            return Optional.empty();
        }
        SourceExpression sourceExpression = sourcingOpt.get();
        String operation = sourceExpression.hasPartialUpdate() ? sourceExpression.partialUpdate() : null;

        Optional<List<JsonNode>> results = evaluateExpression(sourceExpression, rootContext, fieldPath);
        if (results.isEmpty()) {
            return Optional.empty();
        }

        Object value = nodeConverter.convertResults(results.get());
        if (value == null) {
            if (sourceExpression.required()) {
                throw requiredFieldMissing(fieldPath, sourceExpression);
            }
            return Optional.empty();
        }

        return Optional.of(new FieldValue(field.searchField(), value, operation));
    }

    private Optional<FieldValue> processSubdocumentField(
            SearchMapping mapping, BackendEngine engine, JsonNode rootContext, MappedField field, String fieldPath) {
        DocumentMapping childDocument = mapping.document(field.subdocumentType())
                .orElseThrow(() -> new MappingException("Subdocument field '" + field.logicalName()
                        + "' references unknown subdocument type '" + field.subdocumentType() + "'"));

        Optional<SourceExpression> primaryKeyOpt = field.primaryKeyFor(datasourceName);
        if (primaryKeyOpt.isEmpty()) {
            return Optional.empty();
        }
        SourceExpression primaryKeySource = primaryKeyOpt.get();
        String nestedOperation = field.subdocumentPartialUpdateFor(datasourceName).orElse(null);

        Optional<List<JsonNode>> childPrimaryKeys =
                evaluateExpression(primaryKeySource, rootContext, fieldPath + "._id");
        if (childPrimaryKeys.isEmpty()) {
            return Optional.empty();
        }

        int expectedCount = childPrimaryKeys.get().size();
        List<Object> primaryKeyValues = new ArrayList<>(expectedCount);
        for (JsonNode keyNode : childPrimaryKeys.get()) {
            Object key = nodeConverter.convertPrimaryKey(keyNode);
            if (key == null) {
                String message = "Nested document in field '" + fieldPath + "' produced a null primary key";
                log.error(message);
                throw new MappingException(message);
            }
            primaryKeyValues.add(key);
        }

        Map<MappedField, List<Object>> simpleChildResults = new LinkedHashMap<>();
        Map<MappedField, Optional<FieldValue>> nestedChildResults = new LinkedHashMap<>();

        for (MappedField childField : childDocument.fields().values()) {
            String childPath = fieldPath + "." + childField.logicalName();
            if (childField.isSubdocument()) {
                nestedChildResults.put(
                        childField, processSubdocumentField(mapping, engine, rootContext, childField, childPath));
                continue;
            }

            Optional<SourceExpression> childSourcingOpt = childField.sourcingFor(datasourceName);
            if (childSourcingOpt.isEmpty()) {
                continue;
            }
            SourceExpression childSource = childSourcingOpt.get();

            Optional<List<JsonNode>> results = evaluateExpression(childSource, rootContext, childPath);
            if (results.isEmpty()) {
                continue;
            }
            if (results.get().size() != expectedCount) {
                throw new MappingException("Nested field '" + fieldPath + "': child field '"
                        + childField.logicalName() + "' produced " + results.get().size()
                        + " values but primary key produced " + expectedCount);
            }

            List<Object> convertedValues = new ArrayList<>(expectedCount);
            for (JsonNode node : results.get()) {
                Object value = nodeConverter.convertNode(node);
                if (value == null && childSource.required()) {
                    throw requiredFieldMissing(childPath, childSource);
                }
                convertedValues.add(value);
            }
            simpleChildResults.put(childField, convertedValues);
        }

        String nestedIdField = nestedPrimaryKeyField(engine);
        List<Map<String, Object>> nestedDocuments = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            Map<String, Object> nestedDocument = new LinkedHashMap<>();
            nestedDocument.put(nestedIdField, primaryKeyValues.get(i));

            for (Map.Entry<MappedField, List<Object>> entry : simpleChildResults.entrySet()) {
                Object value = entry.getValue().get(i);
                if (value != null) {
                    nestedDocument.put(entry.getKey().searchField(), value);
                }
            }

            for (Optional<FieldValue> nestedResultOpt : nestedChildResults.values()) {
                nestedResultOpt.ifPresent(
                        nestedResult -> nestedDocument.put(nestedResult.target(), nestedResult.value()));
            }

            nestedDocuments.add(nestedDocument);
        }

        if (nestedDocuments.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new FieldValue(field.searchField(), nestedDocuments, nestedOperation));
    }

    private MappedField resolveTargetField(SearchMapping mapping, List<String> hierarchy) {
        if (hierarchy.isEmpty()) {
            throw new MappingException("Partial update hierarchy is empty");
        }

        DocumentMapping currentDocument = mapping.root();
        MappedField current = null;
        for (int i = 0; i < hierarchy.size(); i++) {
            String segment = hierarchy.get(i);
            current = currentDocument.field(segment)
                    .orElseThrow(() -> new MappingException("Field '" + segment
                            + "' not found while resolving partial update hierarchy '"
                            + String.join(".", hierarchy) + "'"));

            if (i < hierarchy.size() - 1) {
                if (!current.isSubdocument()) {
                    throw new MappingException("Field '" + segment
                            + "' is not a subdocument but appears in partial update hierarchy '"
                            + String.join(".", hierarchy) + "'");
                }
                String subdocumentType = current.subdocumentType();
                currentDocument = mapping.document(subdocumentType)
                        .orElseThrow(() -> new MappingException("Subdocument type '" + subdocumentType
                                + "' referenced by partial update hierarchy '"
                                + String.join(".", hierarchy) + "' is not defined"));
            }
        }

        if (current == null || !current.isSubdocument()) {
            throw new MappingException("Partial update hierarchy '" + String.join(".", hierarchy)
                    + "' must resolve to a subdocument field");
        }

        return current;
    }

    private Optional<List<JsonNode>> evaluateExpression(
            SourceExpression expression, JsonNode context, String fieldPath) {
        Optional<List<JsonNode>> results = jqEvaluator.evaluate(expression, context);
        if (results.isEmpty() || results.get().isEmpty()) {
            if (expression.required()) {
                throw requiredFieldMissing(fieldPath, expression);
            }
            return Optional.empty();
        }
        return results;
    }

    private MappingException requiredFieldMissing(String fieldPath, SourceExpression expression) {
        String descriptor = expression.hasJsonPointer()
                ? "json pointer '" + expression.jsonPointer() + "'"
                : "jq expression '" + expression.jq() + "'";
        String message = "Required field '" + fieldPath + "' produced no value using " + descriptor
                + " for datasource '" + datasourceName + "'";
        log.error(message);
        return new MappingException(message);
    }

    private static String nestedPrimaryKeyField(BackendEngine engine) {
        return engine == BackendEngine.SOLR ? "item_id" : "_id";
    }

    private record FieldValue(String target, Object value, String operation) {
    }
}
