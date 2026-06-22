package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class QueryJson {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .nodeFactory(new JsonNodeFactory(true))
                .build();
    }

    private QueryJson() {
    }

    public static ObjectNode boolShould(SearchEngine engine, int minimumMatch, List<JsonNode> clauses) {
        ObjectNode root = JSON.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        clauses.forEach(should::add);
        bool.put(engine.minimumShouldMatchProperty(), minimumMatch);
        return root;
    }

    public static JsonNode shouldOrSingle(SearchEngine engine, List<JsonNode> clauses) {
        return clauses.size() == 1 ? clauses.getFirst() : boolShould(engine, 1, clauses);
    }

    public static ObjectNode boolMust(List<JsonNode> clauses) {
        ObjectNode root = JSON.objectNode();
        ArrayNode must = root.putObject("bool").putArray("must");
        clauses.forEach(must::add);
        return root;
    }

    public static ObjectNode mustNot(SearchEngine engine, JsonNode query) {
        ObjectNode root = JSON.objectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode must = bool.putArray("must");
        if (engine == SearchEngine.ELASTICSEARCH) {
            must.add(JSON.objectNode().set("match_all", JSON.objectNode()));
        } else {
            must.add("*:*");
        }
        bool.putArray("must_not").add(query);
        return root;
    }

    public static ObjectNode solrFieldQuery(String field, Object value) {
        ObjectNode root = JSON.objectNode();
        root.putObject("field")
                .put("f", field)
                .set("query", valueNode(value));
        return root;
    }

    public static JsonNode valueNode(Object value) {
        return switch (value) {
            case String text -> JSON.textNode(text);
            // DecimalNode directly, since JsonNodeFactory.instance normalizes BigDecimals
            case BigDecimal decimal -> DecimalNode.valueOf(decimal);
            case Boolean bool -> JSON.booleanNode(bool);
            case Instant instant -> JSON.textNode(DateTimeFormatter.ISO_INSTANT.format(instant));
            default -> MAPPER.valueToTree(value);
        };
    }
}
