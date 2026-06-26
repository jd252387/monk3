package jd.nomad.index.elasticsearch.percolator;

import jd.nomad.index.elasticsearch.ElasticsearchSinkSettings;

public record PercolatorSinkSettings(
        ElasticsearchSinkSettings elasticsearch,
        String percolatorField,
        int resultSize,
        PercolatorKafkaSettings kafka) {

    public PercolatorSinkSettings {
        if (elasticsearch == null) {
            throw new IllegalStateException("Elasticsearch settings must be provided for the percolator sink");
        }
        if (kafka == null) {
            throw new IllegalStateException("Kafka settings must be provided for the percolator sink");
        }
        percolatorField = percolatorField == null || percolatorField.isBlank() ? "query" : percolatorField.trim();
        resultSize = resultSize <= 0 ? 100 : resultSize;
    }
}
