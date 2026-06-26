package jd.nomad.index.solr;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jd.nomad.config.IndexingConfig;
import jd.nomad.index.IndexSink;
import jd.nomad.index.IndexSinkFactory;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.BackendEngine;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class SolrIndexSinkFactory implements IndexSinkFactory {

    @Inject
    IndexingConfig indexerConfig;

    @Override
    public BackendEngine getEngine() {
        return BackendEngine.SOLR;
    }

    @Override
    public IndexSink create(BackendConfig backend, CamelContext camelContext) {
        String collection = backend.collection();
        if (collection == null || collection.isBlank()) {
            throw new IllegalStateException("Solr backend must declare a collection");
        }
        String chroot = backend.chroot() == null || backend.chroot().isBlank() ? "/" : backend.chroot();
        String baseUrl = backend.url() == null ? null : backend.url().toString();
        if ((backend.zk() == null || backend.zk().isBlank()) && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalStateException("Solr backend must declare either a zk connection or a url");
        }
        SolrSinkSettings settings = new SolrSinkSettings(backend.zk(), collection, chroot, baseUrl);
        return new SolrIndexSink(camelContext, settings, indexerConfig.isPartial());
    }
}
