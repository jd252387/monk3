package jd.nomad.data.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.DatasourceDescriptor;
import jd.nomad.data.ConfigurationParser;
import jd.nomad.data.DataFetcher;
import jd.nomad.data.DataFetcherFactory;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class MongoDataFetcherFactory implements DataFetcherFactory {

    @Override
    public String getType() {
        return "mongodb";
    }

    @Override
    public DataFetcher create(DatasourceDescriptor descriptor, CamelContext camelContext, ObjectMapper objectMapper) {
        JsonNode config = descriptor.configuration().path("mongodb");
        String database = ConfigurationParser.readText(config, "database");
        String collection = ConfigurationParser.readText(config, "collection");
        String client = ConfigurationParser.readText(config, "client");
        MongoDataSourceSettings settings = new MongoDataSourceSettings(database, collection, client);
        return new MongoDataFetcher(camelContext, objectMapper, settings);
    }
}
