package jd.nomad.index.elasticsearch.percolator;

public record PercolatorKafkaSettings(String bootstrapServers, String topic, String clientId) {

    public PercolatorKafkaSettings {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException("Kafka bootstrap servers must be configured for the percolator sink");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("Kafka topic must be configured for the percolator sink");
        }
        bootstrapServers = bootstrapServers.trim();
        topic = topic.trim();
        clientId = clientId != null && !clientId.isBlank() ? clientId.trim() : null;
    }
}
