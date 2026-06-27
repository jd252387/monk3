package jd.nomad.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ConfigurationParser {

    private ConfigurationParser() {
    }

    public static boolean readBoolean(JsonNode node, String field, boolean defaultValue) {
        if (node == null || node.isMissingNode()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        return value == null || value.isMissingNode() ? defaultValue : value.asBoolean(defaultValue);
    }

    public static String readText(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    public static Map<String, String> readStringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (entry.getKey() != null
                    && entry.getValue() != null
                    && !entry.getValue().isNull()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return values;
    }
}
