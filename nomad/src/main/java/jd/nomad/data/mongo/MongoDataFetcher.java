package jd.nomad.data.mongo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jd.nomad.data.DataFetcher;
import jd.nomad.model.IndexEvent;
import lombok.RequiredArgsConstructor;
import org.apache.camel.CamelContext;
import org.apache.camel.FluentProducerTemplate;
import org.apache.camel.component.mongodb.MongoDbConstants;
import org.bson.Document;

@RequiredArgsConstructor
public class MongoDataFetcher implements DataFetcher {
    private final CamelContext camelContext;
    private final ObjectMapper objectMapper;
    private final MongoDataSourceSettings settings;

    @Override
    public Uni<JsonNode> fetch(IndexEvent event, Set<String> fields) {
        String documentId = event.getDatasourceKeyOrPrimary();
        if (documentId == null) {
            return Uni.createFrom()
                    .failure(new IllegalArgumentException(
                            "MongoDB fetcher requires a primary key or datasource key"));
        }

        String database = settings.database().orElse(null);
        String collection = settings.collection().orElse(null);
        String client = settings.client();
        if (database == null || collection == null) {
            return Uni.createFrom()
                    .failure(new IllegalStateException("MongoDB data source must define database and collection"));
        }

        return Uni.createFrom()
                .item(() -> {
                    FluentProducerTemplate template = camelContext.createFluentProducerTemplate();
                    Map<String, Object> headers = new HashMap<>();
                    headers.put(MongoDbConstants.DATABASE, database);
                    headers.put(MongoDbConstants.COLLECTION, collection);
                    headers.put(MongoDbConstants.MONGO_ID, documentId);
                    if (fields != null && !fields.isEmpty()) {
                        Document projection = new Document();
                        fields.forEach(field -> projection.append(field, 1));
                        headers.put(MongoDbConstants.FIELDS_PROJECTION, projection);
                    }

                    String endpoint = String.format("mongodb:%s?operation=findById", client);
                    Object response = template.withHeaders(headers).to(endpoint).request(Object.class);
                    return convertDocument(response);
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private JsonNode convertDocument(Object response) {
        if (response == null) {
            return objectMapper.createObjectNode();
        }
        if (response instanceof Document document) {
            ObjectNode root = objectMapper.createObjectNode();
            document.forEach((key, value) -> root.set(key, convertValue(value)));
            return root;
        }
        return objectMapper.valueToTree(response);
    }

    private JsonNode convertValue(Object value) {
        if (value instanceof Document document) {
            ObjectNode nested = objectMapper.createObjectNode();
            document.forEach((key, v) -> nested.set(key, convertValue(v)));
            return nested;
        }
        if (value instanceof List<?> list) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            list.forEach(item -> arrayNode.add(convertValue(item)));
            return arrayNode;
        }
        if (value instanceof String stringValue) {
            try {
                JsonNode parsed = objectMapper.readTree(stringValue);
                return parsed;
            } catch (Exception ex) {
                return objectMapper.getNodeFactory().textNode(stringValue);
            }
        }
        return objectMapper.valueToTree(value);
    }
}
