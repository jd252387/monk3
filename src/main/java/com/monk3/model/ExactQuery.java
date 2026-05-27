package com.monk3.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk3.search.QueryJson;
import com.monk3.search.QueryParseContext;
import com.monk3.search.SearchEngine;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "An exact-match query against one or more values", oneOf = {ExactQuery.Numeric.class, ExactQuery.Datetime.class, ExactQuery.BooleanValues.class})
public sealed interface ExactQuery<T> extends QueryPayload
        permits ExactQuery.Numeric, ExactQuery.Datetime, ExactQuery.BooleanValues {
    JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    default String type() {
        return "exact";
    }

    @NotEmpty
    List<@NotNull T> values();

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        ObjectNode root = JSON.objectNode();
        ArrayNode fieldValues = root.putObject("terms").putArray(field);
        values().stream().map(QueryJson::valueNode).forEach(fieldValues::add);
        return root;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exact", FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME);
        if (values().size() == 1) {
            ObjectNode root = JSON.objectNode();
            ObjectNode fieldQuery = root.putObject("field");
            fieldQuery.put("f", field).set("query", QueryJson.valueNode(values().getFirst()));
            return root;
        }

        return QueryJson.boolShould(SearchEngine.SOLR, 1, values().stream()
                .map(value -> {
                    ObjectNode root = JSON.objectNode();
                    ObjectNode fieldQuery = root.putObject("field");
                    fieldQuery.put("f", field).set("query", QueryJson.valueNode(value));
                    return root;
                })
                .map(JsonNode.class::cast)
                .toList());
    }

    @Schema(description = "Exact match against numeric values", example = """
            {
              "type": "exact",
              "values": [1995, 2000, 2020]
            }
            """)
    record Numeric(List<BigDecimal> values) implements ExactQuery<BigDecimal> {
    }

    @Schema(description = "Exact match against datetime values (ISO-8601 strings)", example = """
            {
              "type": "exact",
              "values": ["2024-01-01T00:00:00Z", "2024-06-15T12:00:00Z"]
            }
            """)
    record Datetime(List<String> values) implements ExactQuery<String> {
    }

    @Schema(description = "Exact match against boolean values", example = """
            {
              "type": "exact",
              "values": [true]
            }
            """)
    record BooleanValues(List<Boolean> values) implements ExactQuery<Boolean> {
    }
}
