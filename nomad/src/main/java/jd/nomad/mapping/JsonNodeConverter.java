package jd.nomad.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class JsonNodeConverter {

    private final ObjectMapper objectMapper;

    @Inject
    public JsonNodeConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Object convertResults(List<JsonNode> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        if (results.size() == 1) {
            return convertNode(results.getFirst());
        }
        List<Object> values = new ArrayList<>(results.size());
        for (JsonNode node : results) {
            Object value = convertNode(node);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return values;
    }

    public Object convertNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return switch (node.getNodeType()) {
            case NUMBER -> node.numberValue();
            case BOOLEAN -> (Boolean)node.booleanValue();
            case ARRAY -> {
                List<Object> values = new ArrayList<>();
                node.forEach(child -> {
                    Object value = convertNode(child);
                    if (value != null) {
                        values.add(value);
                    }
                });
                yield values;
            }
            case OBJECT -> objectMapper.convertValue(node, Map.class);
            default -> node.asText();
        };
    }

    public Object convertPrimaryKey(JsonNode node) {
        Object value = convertNode(node);
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return value.toString();
    }
}
