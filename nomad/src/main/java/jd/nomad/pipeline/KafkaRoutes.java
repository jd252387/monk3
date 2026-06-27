package jd.nomad.pipeline;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jd.nomad.config.IndexingConfig;
import lombok.RequiredArgsConstructor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.endpoint.dsl.KafkaEndpointBuilderFactory;
import org.apache.camel.builder.endpoint.dsl.ReactiveStreamsEndpointBuilderFactory;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class KafkaRoutes extends RouteBuilder {

    private final IndexingConfig properties;

    @Override
    public void configure() {
        IndexingConfig.Kafka kafka = properties.kafka();
        String consumerEndpoint = KafkaEndpointBuilderFactory.endpointBuilder("kafka", kafka.topic())
                .brokers(kafka.bootstrapServers())
                .groupId(kafka.groupId())
                .maxPollRecords(kafka.maxPollRecords())
                .maxPollIntervalMs(Math.toIntExact(kafka.maxPollInterval().toMillis()))
                .getRawUri();

        String streamEndpoint = ReactiveStreamsEndpointBuilderFactory.endpointBuilder(
                        "reactive-streams", "index-events")
                .getRawUri();

        from(consumerEndpoint)
                .autoStartup(false)
                .routeId("kafka-index-consumer")
                .to(streamEndpoint);
    }
}
