package com.monk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.json.QueryNodeDeserializer;
import com.monk.json.QueryPayloadParser;
import com.monk.search.QueryParseContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Schema(description = "A prefix query matching documents whose field value starts with the given prefix", example = """
        {
          "type": "prefix",
          "prefix": "mach"
        }
        """)
public record PrefixQuery(@NotBlank String prefix) implements QueryPayload {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @JsonProperty
    public String type() {
        return "prefix";
    }

    @Override
    public JsonNode toElasticsearch(QueryParseContext context) {
        String field = context.requireSearchField("prefix");
        ObjectNode root = JSON.objectNode();
        root.putObject("prefix").put(field, prefix);
        return root;
    }

    @Override
    public JsonNode toSolr(QueryParseContext context) {
        String field = context.requireSearchField("prefix");
        ObjectNode root = JSON.objectNode();
        root.putObject("prefixanalyzed").put("f", field).put("v", prefix);
        return root;
    }

    @ApplicationScoped
    public static class Parser implements QueryPayloadParser {
        private static final Set<String> PREFIX_FIELDS = Set.of("type", "prefix");

        @Override
        public String type() {
            return "prefix";
        }

        @Override
        public PrefixQuery parse(JsonParser parser, ObjectMapper mapper, ObjectNode node)
                throws JsonMappingException {
            QueryNodeDeserializer.rejectUnknownFields(parser, node, PREFIX_FIELDS, "prefix query");
            JsonNode prefix = node.get("prefix");
            if (prefix == null || prefix.isNull() || !prefix.isTextual() || prefix.textValue().isBlank()) {
                throw MismatchedInputException.from(parser, Object.class, "Prefix query requires a non-empty 'prefix' string");
            }
            return new PrefixQuery(prefix.textValue());
        }
    }
}
