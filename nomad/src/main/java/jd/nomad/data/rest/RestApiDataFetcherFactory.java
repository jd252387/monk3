package jd.nomad.data.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jd.nomad.config.catalog.DatasourceDescriptor;
import jd.nomad.data.ConfigurationParser;
import jd.nomad.data.DataFetcher;
import jd.nomad.data.DataFetcherFactory;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class RestApiDataFetcherFactory implements DataFetcherFactory {

    @Override
    public String getType() {
        return "rest-api";
    }

    @Override
    public DataFetcher create(DatasourceDescriptor descriptor, CamelContext camelContext, ObjectMapper objectMapper) {
        JsonNode config = descriptor.configuration().path("rest-api");

        String url = ConfigurationParser.readText(config, "url");
        String method = ConfigurationParser.readText(config, "method");
        String bodyTemplate = ConfigurationParser.readText(config, "body-template");
        String contentType = ConfigurationParser.readText(config, "content-type");

        RestApiDataSourceSettings.BasicAuth basicAuth = new RestApiDataSourceSettings.BasicAuth(
                ConfigurationParser.readText(config.path("basic-auth"), "username"),
                ConfigurationParser.readText(config.path("basic-auth"), "password"));

        RestApiDataSourceSettings.TokenAuth tokenAuth = new RestApiDataSourceSettings.TokenAuth(
                ConfigurationParser.readText(config.path("token-auth"), "header"),
                ConfigurationParser.readText(config.path("token-auth"), "prefix"),
                ConfigurationParser.readText(config.path("token-auth"), "token"));

        RestApiDataSourceSettings settings = new RestApiDataSourceSettings(
                url,
                method,
                ConfigurationParser.readStringMap(config.path("headers")),
                ConfigurationParser.readStringMap(config.path("query")),
                bodyTemplate,
                contentType,
                basicAuth,
                tokenAuth);

        return new RestApiDataFetcher(camelContext, objectMapper, settings);
    }
}
