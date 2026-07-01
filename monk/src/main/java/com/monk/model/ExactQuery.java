package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jd.nomad.mapping.FieldType;
import com.monk.json.QueryNodeDeserializer;
import com.monk.json.QueryPayloadParser;
import com.monk.search.QueryJson;
import com.monk.search.QueryParseContext;
import com.monk.search.SearchEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Schema(description = "An exact-match query against one or more values", oneOf = {ExactQuery.Numeric.class, ExactQuery.Datetime.class, ExactQuery.BooleanValues.class})
public sealed interface ExactQuery<T> extends QueryPayload
        permits ExactQuery.Numeric, ExactQuery.Datetime, ExactQuery.BooleanValues {
    Set<FieldType> SUPPORTED_FIELD_TYPES = Set.of(FieldType.STRING, FieldType.NUMBER, FieldType.DATETIME, FieldType.BOOLEAN);
    JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    default String type() {
        return "exact";
    }

    @NotEmpty
    List<@NotNull T> values();

    @Override
    default JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("exact", SUPPORTED_FIELD_TYPES);
        ObjectNode root = JSON.objectNode();
        ArrayNode fieldValues = root.putObject("terms").putArray(field);
        values().stream().map(QueryJson::valueNode).forEach(fieldValues::add);
        return root;
    }

    @Override
    default JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("exact", SUPPORTED_FIELD_TYPES);
        return QueryJson.shouldOrSingle(SearchEngine.SOLR, values().stream()
                .<JsonNode>map(value -> QueryJson.solrFieldQuery(field, value))
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

    @ApplicationScoped
    class Parser implements QueryPayloadParser {
        private static final Set<String> EXACT_FIELDS = Set.of("type", "values");

        @Override
        public String type() {
            return "exact";
        }

        @Override
        public ExactQuery<?> parse(JsonParser parser, ObjectMapper mapper, ObjectNode node)
                throws JsonMappingException {
            QueryNodeDeserializer.rejectUnknownFields(parser, node, EXACT_FIELDS, "exact query");
            JsonNode values = node.get("values");
            if (!values.isArray()) {
                throw MismatchedInputException.from(parser, Object.class, "Exact query values must be an array");
            }
            if (values.isEmpty()) {
                throw MismatchedInputException.from(parser, Object.class, "Exact query values must not be empty");
            }

            List<BigDecimal> numerics = new ArrayList<>();
            List<String> datetimes = new ArrayList<>();
            List<Boolean> booleans = new ArrayList<>();
            for (JsonNode value : values) {
                if (value == null || value.isNull()) {
                    throw MismatchedInputException.from(parser, Object.class, "Exact query values must not contain null");
                }
                if (value.isNumber()) numerics.add(new BigDecimal(value.asText()));
                else if (value.isTextual()) datetimes.add(value.textValue());
                else if (value.isBoolean()) booleans.add(value.booleanValue());
                else throw MismatchedInputException.from(parser, Object.class, "Exact query values must be numbers, timestamps, or booleans");
            }
            if ((!numerics.isEmpty() ? 1 : 0) + (!datetimes.isEmpty() ? 1 : 0) + (!booleans.isEmpty() ? 1 : 0) > 1) {
                throw MismatchedInputException.from(parser, Object.class, "Exact query values must all have the same type");
            }
            if (!numerics.isEmpty()) return new Numeric(List.copyOf(numerics));
            if (!datetimes.isEmpty()) return new Datetime(List.copyOf(datetimes));
            return new BooleanValues(List.copyOf(booleans));
        }
    }
}
