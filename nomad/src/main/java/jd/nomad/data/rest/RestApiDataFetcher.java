package jd.nomad.data.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import jd.nomad.data.DataFetcher;
import jd.nomad.model.IndexEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.FluentProducerTemplate;

@Slf4j
public class RestApiDataFetcher implements DataFetcher {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final CamelContext camelContext;
    private final ObjectMapper objectMapper;
    private final RestApiDataSourceSettings settings;

    public RestApiDataFetcher(
            CamelContext camelContext, ObjectMapper objectMapper, RestApiDataSourceSettings settings) {
        this.camelContext = camelContext;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public Uni<JsonNode> fetch(IndexEvent event, Set<String> fields) {
        String documentId = event.getDatasourceKeyOrPrimary();
        if (documentId == null) {
            return Uni.createFrom()
                    .failure(new IllegalArgumentException(
                            "REST API fetcher requires a primary key or datasource key"));
        }

        String urlTemplate = settings.url().orElse(null);
        if (urlTemplate == null || urlTemplate.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException("REST API data source must define a url"));
        }

        String resolvedUrl = resolveUrl(urlTemplate, documentId, fields);
        String resolvedBody = resolveBody(settings.bodyTemplate().orElse(null), documentId, fields);

        return Uni.createFrom()
                .item(() -> {
                    FluentProducerTemplate template = camelContext.createFluentProducerTemplate();
                    Map<String, Object> headers = new HashMap<>();
                    headers.put(Exchange.HTTP_METHOD, settings.method());
                    headers.putAll(settings.headers());

                    applyAuthentication(headers);

                    String query = buildQuery();
                    if (!query.isEmpty()) {
                        headers.put(Exchange.HTTP_QUERY, query);
                    }

                    settings.contentType()
                            .ifPresent(contentType -> headers.putIfAbsent(Exchange.CONTENT_TYPE, contentType));

                    FluentProducerTemplate producer = template.to(resolvedUrl).withHeaders(headers);
                    if (resolvedBody != null) {
                        producer = producer.withBody(resolvedBody);
                    }

                    String responseBody = producer.request(String.class);
                    try {
                        return objectMapper.readTree(responseBody);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(
                                "Failed to parse REST API response for %s".formatted(resolvedUrl), e);
                    }
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String resolveUrl(String urlTemplate, String documentId, Set<String> fields) {
        String encodedId = URLEncoder.encode(documentId, StandardCharsets.UTF_8);
        String fieldsValue = fields == null || fields.isEmpty() ? "" : String.join(",", fields);
        String encodedFields = URLEncoder.encode(fieldsValue, StandardCharsets.UTF_8);

        String resolved = urlTemplate;
        if (resolved.contains("{id}")) {
            resolved = resolved.replace("{id}", encodedId);
        } else {
            if (resolved.endsWith("/")) {
                resolved = resolved + encodedId;
            } else {
                resolved = resolved + '/' + encodedId;
            }
        }

        if (resolved.contains("{fields}")) {
            resolved = resolved.replace("{fields}", encodedFields);
        }

        return resolved;
    }

    private String resolveBody(String bodyTemplate, String documentId, Set<String> fields) {
        if (bodyTemplate == null || bodyTemplate.isEmpty()) {
            return null;
        }

        String fieldsCommaSeparated = fields == null || fields.isEmpty() ? "" : String.join(",", fields);
        String fieldsJson;
        try {
            fieldsJson = fields == null || fields.isEmpty() ? "[]" : objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            fieldsJson = "[]";
        }

        String resolved = bodyTemplate;
        resolved = resolved.replace("{id}", documentId);
        resolved = resolved.replace("{fields-json}", fieldsJson);
        resolved = resolved.replace("{fields}", fieldsCommaSeparated);

        return resolved;
    }

    private String buildQuery() {
        StringJoiner joiner = new StringJoiner("&");
        settings.query().forEach((key, value) -> {
            if (key != null && value != null) {
                joiner.add(encode(key) + '=' + encode(value));
            }
        });
        return joiner.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void applyAuthentication(Map<String, Object> headers) {
        RestApiDataSourceSettings.TokenAuth tokenAuth = settings.tokenAuth();
        tokenAuth.token().ifPresent(token -> {
            String headerName = tokenAuth.header() == null || tokenAuth.header().isEmpty()
                    ? AUTHORIZATION_HEADER
                    : tokenAuth.header();
            String prefix = tokenAuth.prefix() == null ? "" : tokenAuth.prefix();
            headers.putIfAbsent(headerName, prefix + token);
        });

        RestApiDataSourceSettings.BasicAuth basicAuth = settings.basicAuth();
        if (basicAuth.username().isPresent() && basicAuth.password().isPresent()) {
            if (!headers.containsKey(AUTHORIZATION_HEADER)) {
                String credentials =
                        basicAuth.username().get() + ':' + basicAuth.password().get();
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                headers.put(AUTHORIZATION_HEADER, "Basic " + encoded);
            }
        }
    }
}
