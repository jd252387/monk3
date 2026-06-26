package jd.nomad.index.elasticsearch;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jd.nomad.index.IndexSink;
import jd.nomad.index.exception.SearchEngineExceptions;
import jd.nomad.model.IndexCommand;
import org.apache.camel.CamelContext;
import org.apache.camel.FluentProducerTemplate;
import org.apache.camel.builder.endpoint.dsl.ElasticsearchEndpointBuilderFactory;
import org.apache.camel.component.es.ElasticsearchConstants;

public class ElasticsearchIndexSink implements IndexSink {

    private final CamelContext camelContext;
    private final ElasticsearchSinkSettings settings;
    private final boolean isPartial;
    private static final String ROUTING_HEADER = "routing";

    public ElasticsearchIndexSink(CamelContext camelContext, ElasticsearchSinkSettings settings, boolean isPartial) {
        this.camelContext = camelContext;
        this.settings = settings;
        this.isPartial = isPartial;
    }

    @Override
    public Uni<Void> indexBatch(List<IndexCommand> commands) {
        if (commands.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        List<Uni<Void>> operations = commands.stream().map(this::processCommand).toList();
        return Uni.combine().all().unis(operations).discardItems();
    }

    private Uni<Void> processCommand(IndexCommand command) {
        if (command.getFields().isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        if (!isPartial) {
            // Full document indexing (Index operation)
            Map<String, Object> document = buildDocument(command);
            return sendIndex(command, document);
        } else {
            // Partial update with scripts
            return sendPartialUpdate(command);
        }
    }

    private Uni<Void> sendIndex(IndexCommand command, Map<String, Object> document) {
        return Uni.createFrom()
                .item(() -> {
                    String endpoint = buildEndpoint("Index", command);
                    FluentProducerTemplate template = camelContext
                            .createFluentProducerTemplate()
                            .withHeader(ElasticsearchConstants.PARAM_INDEX_ID, command.getPrimaryKey());
                    if (command.getRootId() != null && !command.getRootId().isBlank()) {
                        template = template.withHeader(ROUTING_HEADER, command.getRootId());
                    }
                    try {
                        template.withBody(document).to(endpoint).request(Object.class);
                    } catch (Exception e) {
                        throw SearchEngineExceptions.classify(
                                "Failed to index document %s into Elasticsearch target %s"
                                        .formatted(command.getPrimaryKey(), endpoint),
                                e);
                    }
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    private Uni<Void> sendPartialUpdate(IndexCommand command) {
        return Uni.createFrom()
                .item(() -> {
                    String endpoint = buildEndpoint("Update", command);

                    // Build Painless script and params
                    Map<String, Object> scriptPayload = buildScriptPayload(command);

                    FluentProducerTemplate template = camelContext
                            .createFluentProducerTemplate()
                            .withHeader(ElasticsearchConstants.PARAM_INDEX_ID, command.getPrimaryKey());
                    if (command.getRootId() != null && !command.getRootId().isBlank()) {
                        template = template.withHeader(ROUTING_HEADER, command.getRootId());
                    }
                    try {
                        template.withBody(scriptPayload).to(endpoint).request(Object.class);
                    } catch (Exception e) {
                        throw SearchEngineExceptions.classify(
                                "Failed to update document %s in Elasticsearch target %s"
                                        .formatted(command.getPrimaryKey(), endpoint),
                                e);
                    }
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    private Map<String, Object> buildDocument(IndexCommand command) {
        Map<String, Object> document = new LinkedHashMap<>();
        command.getFields().forEach((key, updateField) -> document.put(key, updateField.value()));
        return document;
    }

    private Map<String, Object> buildScriptPayload(IndexCommand command) {
        StringBuilder script = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();

        // Process all fields uniformly
        for (Map.Entry<String, jd.nomad.model.UpdateField> entry :
                command.getFields().entrySet()) {
            String fieldName = entry.getKey();
            jd.nomad.model.UpdateField updateField = entry.getValue();
            Object value = updateField.value();
            String paramName = "param_" + fieldName.replaceAll("[^a-zA-Z0-9]", "_");

            // Determine which operation to use
            String operation;
            if (updateField.isPartialUpdate()) {
                // Use the specific partial update operation
                operation = updateField.operation();
            } else {
                // Regular field, use "set"
                operation = "set";
            }

            String painlessScript = generatePainlessScript(fieldName, paramName, operation, value);
            script.append(painlessScript);

            params.put(paramName, value);
        }

        Map<String, Object> scriptObj = new LinkedHashMap<>();
        scriptObj.put("source", script.toString());
        scriptObj.put("params", params);
        scriptObj.put("lang", "painless");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("script", scriptObj);

        if (settings.docAsUpsert()) {
            payload.put("scripted_upsert", Boolean.TRUE);
            payload.put("upsert", new LinkedHashMap<>());
        }

        return payload;
    }

    private String generatePainlessScript(String fieldName, String paramName, String operation, Object value) {
        return switch (operation) {
            case "add" -> // Add to array/list
                    "if (ctx._source." + fieldName + " == null) { ctx._source." + fieldName + " = []; } "
                            + "if (params." + paramName + " instanceof List) { ctx._source." + fieldName
                            + ".addAll(params." + paramName + "); } else { ctx._source." + fieldName
                            + ".add(params." + paramName + "); }";
            case "add-distinct" -> // Add only if not exists
                    "if (ctx._source." + fieldName + " == null) { ctx._source." + fieldName + " = []; } "
                            + "if (params." + paramName + " instanceof List) { for (item in params." + paramName
                            + ") { if (!ctx._source." + fieldName + ".contains(item)) { ctx._source." + fieldName
                            + ".add(item); } } } else { if (!ctx._source." + fieldName + ".contains(params."
                            + paramName + ")) { ctx._source." + fieldName + ".add(params." + paramName + "); } }";
            case "remove" -> // Remove from array
                    "if (ctx._source." + fieldName + " != null) { if (params." + paramName
                            + " instanceof List) { ctx._source." + fieldName + ".removeAll(params." + paramName
                            + "); } else { ctx._source." + fieldName + ".removeIf(item -> item == params."
                            + paramName + "); } }";
            case "removeregex" -> // Remove by regex pattern
                    "if (ctx._source." + fieldName + " != null && params." + paramName
                            + " instanceof String) { ctx._source." + fieldName
                            + ".removeIf(item -> item.toString().matches(params." + paramName + ")); }";
            case "inc" -> // Increment numeric value
                    "if (ctx._source." + fieldName + " == null) { ctx._source." + fieldName + " = 0; } " + "ctx._source."
                            + fieldName + " += params." + paramName + ";";
            case "set" -> // Set value (default)
                    "ctx._source." + fieldName + " = params." + paramName + ";";
            default -> // Default to set
                    "ctx._source." + fieldName + " = params." + paramName + ";";
        };
    }

    private String buildEndpoint(String operation, IndexCommand command) {
        String cluster = settings.cluster();
        String indexName = resolveIndexName();
        String id = command.getPrimaryKey();
        if (id == null) {
            throw new IllegalArgumentException("Primary key is required for Elasticsearch indexing");
        }
        List<String> hosts = settings.hosts();
        if (hosts.isEmpty()) {
            throw new IllegalStateException("Elasticsearch hosts must be configured");
        }
        return ElasticsearchEndpointBuilderFactory.endpointBuilder("elasticsearch", cluster)
                .operation(operation)
                .indexName(encode(indexName))
                .hostAddresses(String.join(",", hosts))
                .getRawUri();
    }

    private String resolveIndexName() {
        if (settings.index() == null || settings.index().isBlank()) {
            throw new IllegalStateException("Elasticsearch index must be configured");
        }
        return settings.index();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
