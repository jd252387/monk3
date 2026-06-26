package jd.nomad.data.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.DatasourceDescriptor;
import jd.nomad.data.ConfigurationParser;
import jd.nomad.data.DataFetcher;
import jd.nomad.data.DataFetcherFactory;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class S3DataFetcherFactory implements DataFetcherFactory {

    @Override
    public String getType() {
        return "s3";
    }

    @Override
    public DataFetcher create(DatasourceDescriptor descriptor, CamelContext camelContext, ObjectMapper objectMapper) {
        JsonNode config = descriptor.configuration().path("s3");
        String bucket = ConfigurationParser.readText(config, "bucket");
        String keyTemplate = ConfigurationParser.readText(config, "key-template");
        S3DataSourceSettings settings = new S3DataSourceSettings(bucket, keyTemplate);
        return new S3DataFetcher(camelContext, objectMapper, settings);
    }
}
