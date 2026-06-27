package jd.nomad.pipeline;

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import jd.nomad.config.IndexingConfig;
import jd.nomad.data.DataFetcher;
import jd.nomad.index.IndexSink;
import jd.nomad.index.exception.SearchEngineRequestException;
import jd.nomad.index.exception.SearchEngineUnavailableException;
import jd.nomad.mapping.MappingEngine;
import jd.nomad.mapping.MappingFieldCollector;
import jd.nomad.model.IndexCommand;
import jd.nomad.model.IndexEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mutiny.zero.flow.adapters.AdaptersToFlow;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.reactive.streams.api.CamelReactiveStreamsService;

@Slf4j
@ApplicationScoped
@Startup
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class IndexingStream {

    private final CamelReactiveStreamsService reactiveStreamsService;
    private final IndexEventDeserializer eventDeserializer;
    private final MappingEngine mappingEngine;
    private final IndexSink indexSink;
    private final IndexingConfig properties;
    private final CamelContext camelContext;
    private final DataFetcher dataFetcher;
    private final MappingFieldCollector mappingFieldCollector;

    private Set<String> fieldsToFetch;
    private Cancellable subscription;
    private final AtomicReference<Instant> lastSuccessfulIndex = new AtomicReference<>(Instant.now());

    @PostConstruct
    public void start() throws Exception {
        IndexingConfig.Pipeline pipeline = properties.pipeline();
        fieldsToFetch = mappingFieldCollector.collectFields();

        Multi<Exchange> rawStream = Multi.createFrom()
                .publisher(AdaptersToFlow.publisher(reactiveStreamsService.fromStream("index-events", Exchange.class)));
        Multi<IndexEvent> events = rawStream
                .onItem()
                .transformToUni(this::deserializeEvent)
                .merge()
                .select()
                .where(Optional::isPresent)
                .map(Optional::get);

        Multi<IndexCommand> commands = events.onItem()
                .transformToUni(this::fetchAndMap)
                .withRequests(pipeline.prefetch())
                .merge(pipeline.concurrency())
                .select()
                .where(Optional::isPresent)
                .map(Optional::get);

        subscription = commands.onItem()
                .transformToUni(this::submitCommand)
                .merge(pipeline.concurrency())
                .subscribe()
                .with(ignored -> {}, error -> log.atError().setCause(error).log("Unrecoverable indexing error"));

        camelContext.getRouteController().startRoute("kafka-index-consumer");
    }

    private Uni<Optional<IndexEvent>> deserializeEvent(Exchange exchange) {
        return Uni.createFrom().item(() -> {
            String payload = null;
            try {
                payload = exchange.getMessage().getBody(String.class);
                return eventDeserializer.deserialize(payload);
            } catch (Exception ex) {
                log.atError()
                        .addKeyValue("payload", payload)
                        .setCause(ex)
                        .log("Failed to read Kafka payload as text. Acknowledging event.");
                return Optional.empty();
            }
        });
    }

    private Uni<Optional<IndexCommand>> fetchAndMap(IndexEvent event) {
        return dataFetcher
                .fetch(event, fieldsToFetch)
                .onItem()
                .transform(
                        fetchedDocument -> mappingEngine.map(event.getPrimaryKey(), event.getRootId(), fetchedDocument))
                .map(Optional::of)
                .onFailure()
                .invoke(ex -> log.atError()
                        .addKeyValue("event", event)
                        .setCause(ex)
                        .log("Failed to process event. Acknowledging event."))
                .onFailure()
                .recoverWithItem(Optional.empty());
    }

    private Uni<Void> submitCommand(IndexCommand command) {
        return attemptIndex(command)
                .onFailure(SearchEngineRequestException.class)
                .invoke(ex -> log.atError()
                        .addKeyValue("primaryKey", command.getPrimaryKey())
                        .setCause(ex)
                        .log("Search engine rejected command. Acknowledging."))
                .onFailure()
                .invoke(ex -> log.atError()
                        .addKeyValue("primaryKey", command.getPrimaryKey())
                        .setCause(ex)
                        .log("Unexpected indexing failure for command"))
                .onFailure()
                .recoverWithUni(ex -> Uni.createFrom().voidItem());
    }

    private Uni<Void> attemptIndex(IndexCommand command) {
        return Uni.createFrom()
                .deferred(() -> indexSink.indexBatch(List.of(command)))
                .invoke(() -> lastSuccessfulIndex.set(Instant.now()))
                .onFailure(SearchEngineUnavailableException.class)
                .recoverWithUni(ex -> handleSearchEngineUnavailable(command, (SearchEngineUnavailableException) ex));
    }

    private Uni<Void> handleSearchEngineUnavailable(IndexCommand command, SearchEngineUnavailableException ex) {
        Duration retryInterval = properties.pipeline().searchEngineRetryInterval();
        Duration failureThreshold = properties.pipeline().searchEngineFailureThreshold();
        Instant now = Instant.now();
        Duration sinceLastSuccess = Duration.between(lastSuccessfulIndex.get(), now);

        if (sinceLastSuccess.compareTo(failureThreshold) < 0) {
            log.atWarn()
                    .addKeyValue("primaryKey", command.getPrimaryKey())
                    .addKeyValue("sinceLastSuccess", sinceLastSuccess)
                    .addKeyValue("retryInterval", retryInterval)
                    .setCause(ex)
                    .log("Search engine unavailable for command. Retrying.");

            return Uni.createFrom()
                    .voidItem()
                    .onItem()
                    .delayIt()
                    .by(retryInterval)
                    .chain(() -> attemptIndex(command));
        }

        log.atError()
                .addKeyValue("primaryKey", command.getPrimaryKey())
                .log("Search engine unavailable for command. Acknowledging.");
        return Uni.createFrom().voidItem();
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) {
            subscription.cancel();
        }
    }
}
