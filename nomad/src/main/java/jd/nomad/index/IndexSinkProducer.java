package jd.nomad.index;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jd.nomad.config.IndexingConfig;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.mapping.BackendConfig;
import jd.nomad.mapping.BackendEngine;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class IndexSinkProducer implements IndexSink {
    private final ConfigurationCatalogService catalogService;
    private final IndexingConfig indexerConfig;
    private final CamelContext camelContext;
    private final Instance<IndexSinkFactory> factories;

    @Delegate
    private volatile IndexSink activeIndexSink;

    public void updateIndexSink() {
        String backendName = indexerConfig
                .backend()
                .orElseGet(() -> catalogService.backendForMaterialType(indexerConfig.materialType()));
        BackendConfig backend = catalogService.backendConfig(backendName);
        BackendEngine engine = backend.engine();

        IndexSink updatedSink = factories.stream()
                .filter(factory -> factory.getEngine() == engine)
                .findFirst()
                .map(factory -> factory.create(backend, camelContext))
                .orElseThrow(() -> new IllegalStateException("Unsupported backend engine '" + engine + "'"));

        log.info("Produced index sink for backend '{}' using engine '{}'", backendName, engine);

        this.activeIndexSink = updatedSink;
    }

    @PostConstruct
    public void init() {
        updateIndexSink();
        catalogService.registerListener(this::updateIndexSink);
    }
}
