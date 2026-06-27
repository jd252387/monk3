package jd.nomad.index.elasticsearch.percolator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jd.nomad.index.IndexSink;
import jd.nomad.index.elasticsearch.ElasticsearchSinkSettings;
import jd.nomad.index.exception.SearchEngineExceptions;
import jd.nomad.model.IndexCommand;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.FluentProducerTemplate;
import org.apache.camel.builder.endpoint.dsl.ElasticsearchEndpointBuilderFactory;
import org.apache.camel.builder.endpoint.dsl.KafkaEndpointBuilderFactory;
import org.apache.camel.component.kafka.KafkaConstants;

@Slf4j
public class ElasticsearchPercolatorIndexSink implements IndexSink {

    private final CamelContext camelContext;
    private final ObjectMapper objectMapper;
    private final ElasticsearchSinkSettings elasticsearchSettings;
    private final PercolatorSinkSettings settings;
    private final String searchEndpointUri;
    private final String kafkaEndpointUri;

    public ElasticsearchPercolatorIndexSink(
            CamelContext camelContext, ObjectMapper objectMapper, PercolatorSinkSettings settings) {
        this.camelContext = camelContext;
        this.objectMapper = objectMapper;
        this.elasticsearchSettings = settings.elasticsearch();
        this.settings = settings;
        this.searchEndpointUri = buildSearchEndpointUri(this.elasticsearchSettings);
        this.kafkaEndpointUri = buildKafkaEndpointUri(settings.kafka());
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
        Map<String, Object> document = buildDocument(command);
        return executePercolate(document)
                .chain(matches -> publishMatches(command, matches))
                .onFailure()
                .invoke(error -> log.atError()
                        .addKeyValue("primaryKey", command.getPrimaryKey())
                        .setCause(error)
                        .log("Failed to process percolator sink command"));
    }

    private Uni<List<String>> executePercolate(Map<String, Object> document) {
        return Uni.createFrom()
                .item(() -> {
                    Map<String, Object> percolateQuery = Map.of(
                            "percolate",
                            Map.of("field", settings.percolatorField(), "document", document));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("query", percolateQuery);
                    body.put("size", settings.resultSize());

                    FluentProducerTemplate template = camelContext.createFluentProducerTemplate();
                    try {
                        Object response = template.withBody(body).to(searchEndpointUri).request(Object.class);
                        return PercolatorResponseParser.extractMatchIds(response, objectMapper);
                    } catch (Exception e) {
                        throw SearchEngineExceptions.classify(
                                "Failed to execute percolate query against Elasticsearch index '" +
                                        elasticsearchSettings.index() + "'",
                                e);
                    }
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Uni<Void> publishMatches(IndexCommand command, List<String> matches) {
        return Uni.createFrom()
                .item(() -> {
                    String payload = writePayload(matches);
                    FluentProducerTemplate template = camelContext.createFluentProducerTemplate().to(kafkaEndpointUri);
                    if (command.getPrimaryKey() != null && !command.getPrimaryKey().isBlank()) {
                        template = template.withHeader(
                                KafkaConstants.KEY, command.getPrimaryKey().getBytes(StandardCharsets.UTF_8));
                    }
                    template.withBody(payload.getBytes(StandardCharsets.UTF_8)).request();
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    private String writePayload(List<String> matches) {
        try {
            return objectMapper.writeValueAsString(matches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize percolator matches", e);
        }
    }

    private Map<String, Object> buildDocument(IndexCommand command) {
        Map<String, Object> document = new LinkedHashMap<>();
        command.getFields().forEach((field, value) -> document.put(field, value.value()));
        if (command.getPrimaryKey() != null && !command.getPrimaryKey().isBlank()) {
            document.putIfAbsent("_id", command.getPrimaryKey());
        }
        if (command.getRootId() != null && !command.getRootId().isBlank()) {
            document.putIfAbsent("_routing", command.getRootId());
        }
        return document;
    }

    private String buildSearchEndpointUri(ElasticsearchSinkSettings settings) {
        String index = settings.index();
        if (index == null || index.isBlank()) {
            throw new IllegalStateException("Elasticsearch index must be configured for the percolator sink");
        }
        if (settings.hosts().isEmpty()) {
            throw new IllegalStateException("Elasticsearch hosts must be configured for the percolator sink");
        }
        return ElasticsearchEndpointBuilderFactory.endpointBuilder("elasticsearch", settings.cluster())
                .operation("Search")
                .indexName(encode(index))
                .hostAddresses(String.join(",", settings.hosts()))
                .getRawUri();
    }

    private String buildKafkaEndpointUri(PercolatorKafkaSettings kafkaSettings) {
        KafkaEndpointBuilderFactory.KafkaEndpointBuilder builder = KafkaEndpointBuilderFactory
                .endpointBuilder("kafka", kafkaSettings.topic())
                .brokers(kafkaSettings.bootstrapServers());
        if (kafkaSettings.clientId() != null) {
            builder = builder.clientId(kafkaSettings.clientId());
        }
        return builder.getRawUri();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
