package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public final class QueryJson {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

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

    public static JsonNode valueNode(Object value) {
        return switch (value) {
            case String string -> JSON.textNode(string);
            case Boolean bool -> JSON.booleanNode(bool);
            case Integer integer -> JSON.numberNode(integer);
            case Long longValue -> JSON.numberNode(longValue);
            case BigInteger bigInteger -> JSON.numberNode(bigInteger);
            case BigDecimal bigDecimal -> JSON.numberNode(bigDecimal);
            case Float floatValue -> JSON.numberNode(floatValue);
            case Double doubleValue -> JSON.numberNode(doubleValue);
            case Number number -> JSON.numberNode(number.doubleValue());
            default -> throw new IllegalArgumentException("Query value must be a string, number, or boolean");
        };
    }
}
