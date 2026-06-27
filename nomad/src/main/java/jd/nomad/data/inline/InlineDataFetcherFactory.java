package jd.nomad.data.inline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.DatasourceDescriptor;
import jd.nomad.data.DataFetcher;
import jd.nomad.data.DataFetcherFactory;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class InlineDataFetcherFactory implements DataFetcherFactory {

    @Override
    public String getType() {
        return "inline";
    }

    @Override
    public DataFetcher create(DatasourceDescriptor descriptor, CamelContext camelContext, ObjectMapper objectMapper) {
        return new InlineDocumentFetcher();
    }
}
