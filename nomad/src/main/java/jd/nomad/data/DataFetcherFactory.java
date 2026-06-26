package jd.nomad.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import jd.nomad.config.catalog.DatasourceDescriptor;
import org.apache.camel.CamelContext;

public interface DataFetcherFactory {

    /**
     * Returns the datasource type this factory supports (e.g., "s3", "mongodb", "inline").
     */
    String getType();

    /**
     * Creates a DataFetcher instance for the given datasource descriptor.
     *
     * @param descriptor   the datasource descriptor containing configuration
     * @param camelContext the Camel context for routing/integration
     * @param objectMapper the JSON object mapper
     * @return a configured DataFetcher instance
     */
    DataFetcher create(DatasourceDescriptor descriptor, CamelContext camelContext, ObjectMapper objectMapper);
}
