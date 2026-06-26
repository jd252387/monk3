package jd.nomad.data.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jd.nomad.data.DataFetcher;
import jd.nomad.model.IndexEvent;
import org.apache.camel.CamelContext;
import org.apache.camel.FluentProducerTemplate;
import org.apache.camel.builder.endpoint.dsl.AWS2S3EndpointBuilderFactory;
import org.apache.camel.component.aws2.s3.AWS2S3Constants;

public class S3DataFetcher implements DataFetcher {

    private final CamelContext camelContext;
    private final ObjectMapper objectMapper;
    private final S3DataSourceSettings settings;

    public S3DataFetcher(CamelContext camelContext, ObjectMapper objectMapper, S3DataSourceSettings settings) {
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
                            "S3 fetcher requires a primary key or datasource key"));
        }

        String bucket = settings.bucket().orElse(null);
        if (bucket == null || bucket.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException("S3 data source must define a bucket"));
        }
        String key = settings.resolveKey(documentId);
        if (key == null || key.isEmpty()) {
            return Uni.createFrom().failure(new IllegalStateException("S3 data source produced an empty object key"));
        }

        return Uni.createFrom()
                .item(() -> {
                    FluentProducerTemplate template = camelContext.createFluentProducerTemplate();
                    Map<String, Object> headers = new HashMap<>();
                    headers.put(AWS2S3Constants.KEY, key);
                    try {
                        String endpoint = AWS2S3EndpointBuilderFactory.endpointBuilder("aws2-s3", bucket)
                                .operation("getObject")
                                .getRawUri();
                        byte[] payload =
                                template.withHeaders(headers).to(endpoint).request(byte[].class);
                        return objectMapper.readTree(payload);
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Failed to fetch document %s from bucket %s".formatted(key, bucket), e);
                    }
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
