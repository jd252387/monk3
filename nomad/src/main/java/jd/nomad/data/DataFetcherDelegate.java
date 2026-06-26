package jd.nomad.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jd.nomad.config.IndexingConfig;
import jd.nomad.config.catalog.ConfigurationCatalogService;
import jd.nomad.config.catalog.DatasourceDescriptor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;

@Slf4j
@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DataFetcherDelegate implements DataFetcher {
    private final ConfigurationCatalogService catalogService;
    private final IndexingConfig indexerConfig;
    private final CamelContext camelContext;
    private final ObjectMapper objectMapper;
    private final Instance<DataFetcherFactory> factories;

    @Delegate
    private volatile DataFetcher activeDataFetcher;

    private void updateDataFetcher() {
        DatasourceDescriptor descriptor = catalogService.datasource(indexerConfig.dataSource());
        String type = descriptor.type();

        DataFetcher dataFetcher = factories.stream()
                .filter(factory -> factory.getType().equalsIgnoreCase(type))
                .findFirst()
                .map(factory -> factory.create(descriptor, camelContext, objectMapper))
                .orElseThrow(() -> new IllegalStateException("Unsupported datasource type '" + type + "'"));

        log.info("Produced data fetcher for datasource '{}' using type '{}'", descriptor.name(), descriptor.type());

        this.activeDataFetcher = dataFetcher;
    }

    @PostConstruct
    public void init() {
        updateDataFetcher();
        catalogService.registerListener(this::updateDataFetcher);
    }
}
