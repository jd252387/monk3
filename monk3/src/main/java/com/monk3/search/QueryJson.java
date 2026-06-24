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

    /**
     * Builds a single {@code bool} query from pre-translated should/must/must_not clauses.
     * {@code minimumShouldMatch} is emitted only when there are should clauses. When the query is
     * purely negative (must_not only), an engine match-all clause is added to {@code must} so the
     * bool matches every document before excluding.
     */
    public static ObjectNode bool(
            SearchEngine engine,
            int minimumShouldMatch,
            List<JsonNode> should,
            List<JsonNode> must,
            List<JsonNode> mustNot) {
        ObjectNode root = JSON.objectNode();
        ObjectNode bool = root.putObject("bool");
        if (!should.isEmpty()) {
            ArrayNode shouldArray = bool.putArray("should");
            should.forEach(shouldArray::add);
            bool.put(engine.minimumShouldMatchProperty(), minimumShouldMatch);
        }
        if (!mustNot.isEmpty() && should.isEmpty() && must.isEmpty()) {
            bool.putArray("must").add(matchAll(engine));
        } else if (!must.isEmpty()) {
            ArrayNode mustArray = bool.putArray("must");
            must.forEach(mustArray::add);
        }
        if (!mustNot.isEmpty()) {
            ArrayNode mustNotArray = bool.putArray("must_not");
            mustNot.forEach(mustNotArray::add);
        }
        return root;
    }

    private static JsonNode matchAll(SearchEngine engine) {
        return engine == SearchEngine.ELASTICSEARCH
                ? JSON.objectNode().set("match_all", JSON.objectNode())
                : JSON.textNode("*:*");
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
