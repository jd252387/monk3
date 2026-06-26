package jd.nomad.index.elasticsearch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;

import jd.nomad.config.IndexingConfig;
import jd.nomad.index.IndexSink;
import jd.nomad.index.IndexSinkFactory;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.BackendEngine;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class ElasticsearchIndexSinkFactory implements IndexSinkFactory {

    @Inject
    IndexingConfig indexerConfig;

    @Override
    public BackendEngine getEngine() {
        return BackendEngine.ELASTICSEARCH;
    }

    @Override
    public IndexSink create(BackendConfig backend, CamelContext camelContext) {
        List<String> hosts = backend.hosts().stream().map(URI::toString).toList();
        if (hosts.isEmpty() && backend.url() != null) {
            hosts = List.of(backend.url().toString());
        }
        if (hosts.isEmpty()) {
            throw new IllegalStateException("Elasticsearch backend must declare either hosts or a url");
        }
        String index = backend.index();
        if (index == null || index.isBlank()) {
            throw new IllegalStateException("Elasticsearch backend must declare an index");
        }
        // BackendConfig carries no cluster/doc-as-upsert; use the index name as a stable cluster label and
        // default doc-as-upsert on (matching the previous sink behaviour).
        ElasticsearchSinkSettings settings = new ElasticsearchSinkSettings(index, hosts, index, true);
        return new ElasticsearchIndexSink(camelContext, settings, indexerConfig.isPartial());
    }
}
