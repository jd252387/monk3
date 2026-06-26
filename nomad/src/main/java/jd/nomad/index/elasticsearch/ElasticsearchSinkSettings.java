package jd.nomad.index.elasticsearch;

import java.util.List;

public record ElasticsearchSinkSettings(String cluster, List<String> hosts, String index, boolean docAsUpsert) {

    public ElasticsearchSinkSettings(String cluster, List<String> hosts, String index, boolean docAsUpsert) {
        this.cluster = cluster == null || cluster.isBlank() ? "default" : cluster;
        this.hosts = hosts == null ? List.of() : List.copyOf(hosts);
        this.index = index;
        this.docAsUpsert = docAsUpsert;
    }
}
