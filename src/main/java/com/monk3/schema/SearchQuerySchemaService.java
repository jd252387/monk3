package com.monk3.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.monk3.model.SearchQueryRequest;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SearchQuerySchemaService {
    private static final String SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema";
    private static final String SCHEMA_ID = "https://example.com/schemas/search-query-dsl.schema.json";

    private final ObjectMapper objectMapper;
    private final SchemaGenerator schemaGenerator;

    public SearchQuerySchemaService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SchemaGeneratorConfig config = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON
        )
                .with(new JacksonModule())
                .with(new JakartaValidationModule())
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                .build();
        this.schemaGenerator = new SchemaGenerator(config);
    }

    public JsonNode generateSchema() {
        ObjectNode schema = (ObjectNode) schemaGenerator.generateSchema(SearchQueryRequest.class);
        schema.put("$schema", SCHEMA_URI);
        schema.put("$id", SCHEMA_ID);
        schema.put("title", "Search Query DSL");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("required", array("name", "materialTypes", "query"));

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("id", stringSchema());
        properties.set("name", stringSchema());
        properties.set("materialTypes", materialTypesSchema());
        properties.set("query", ref("QueryNode"));
        schema.set("properties", properties);

        ObjectNode definitions = objectMapper.createObjectNode();
        definitions.set("QueryNode", queryNodeSchema());
        definitions.set("QueryPayload", queryPayloadSchema());
        definitions.set("TextQuery", textQuerySchema());
        definitions.set("RangeQuery", rangeQuerySchema());
        schema.set("$defs", definitions);
        return schema;
    }

    private ObjectNode queryNodeSchema() {
        ObjectNode schema = objectSchema();
        schema.set("required", array("field", "data"));

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("field", stringSchema());
        properties.set("minimumMatch", integerSchema());
        properties.set("isNot", booleanSchema());
        properties.set("data", objectMapper.createObjectNode());
        schema.set("properties", properties);

        ObjectNode ifNode = objectMapper.createObjectNode();
        ObjectNode ifProperties = objectMapper.createObjectNode();
        ObjectNode fieldConst = objectMapper.createObjectNode();
        fieldConst.put("const", "");
        ifProperties.set("field", fieldConst);
        ifNode.set("properties", ifProperties);

        ObjectNode thenNode = objectMapper.createObjectNode();
        ObjectNode thenProperties = objectMapper.createObjectNode();
        thenProperties.set("data", booleanDataSchema());
        thenNode.set("properties", thenProperties);

        ObjectNode elseNode = objectMapper.createObjectNode();
        ObjectNode elseProperties = objectMapper.createObjectNode();
        elseProperties.set("data", ref("QueryPayload"));
        elseNode.set("properties", elseProperties);

        ObjectNode conditional = objectMapper.createObjectNode();
        conditional.set("if", ifNode);
        conditional.set("then", thenNode);
        conditional.set("else", elseNode);
        ArrayNode allOf = objectMapper.createArrayNode();
        allOf.add(conditional);
        schema.set("allOf", allOf);
        return schema;
    }

    private ObjectNode booleanDataSchema() {
        ObjectNode outer = objectMapper.createObjectNode();
        outer.put("type", "array");
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put("type", "array");
        inner.set("items", ref("QueryNode"));
        outer.set("items", inner);
        return outer;
    }

    private ObjectNode queryPayloadSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode oneOf = objectMapper.createArrayNode();
        oneOf.add(ref("TextQuery"));
        oneOf.add(ref("RangeQuery"));
        schema.set("oneOf", oneOf);
        return schema;
    }

    private ObjectNode textQuerySchema() {
        ObjectNode schema = objectSchema();
        schema.set("required", array("type", "phrases"));
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("type", constString("text"));
        ObjectNode phrases = objectMapper.createObjectNode();
        phrases.put("type", "array");
        phrases.set("items", stringSchema());
        properties.set("phrases", phrases);
        schema.set("properties", properties);
        return schema;
    }

    private ObjectNode rangeQuerySchema() {
        ObjectNode schema = objectSchema();
        schema.set("required", array("type"));
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("type", constString("range"));
        properties.set("gte", rangeBoundSchema());
        properties.set("gt", rangeBoundSchema());
        properties.set("lte", rangeBoundSchema());
        properties.set("lt", rangeBoundSchema());
        schema.set("properties", properties);

        ArrayNode anyOf = objectMapper.createArrayNode();
        anyOf.add(requiredOnly("gte"));
        anyOf.add(requiredOnly("gt"));
        anyOf.add(requiredOnly("lte"));
        anyOf.add(requiredOnly("lt"));
        schema.set("anyOf", anyOf);

        ArrayNode conflictingBounds = objectMapper.createArrayNode();
        conflictingBounds.add(requiredOnly("gte", "gt"));
        conflictingBounds.add(requiredOnly("lte", "lt"));
        ObjectNode not = objectMapper.createObjectNode();
        not.set("anyOf", conflictingBounds);
        schema.set("not", not);
        return schema;
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode materialTypesSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "array");
        schema.put("minItems", 1);
        schema.set("items", stringSchema());
        return schema;
    }

    private ObjectNode stringSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "string");
        return schema;
    }

    private ObjectNode integerSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "integer");
        return schema;
    }

    private ObjectNode booleanSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "boolean");
        return schema;
    }

    private ObjectNode rangeBoundSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode types = objectMapper.createArrayNode();
        types.add("number");
        types.add("string");
        schema.set("type", types);
        return schema;
    }

    private ObjectNode constString(String value) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("const", value);
        return schema;
    }

    private ObjectNode ref(String definitionName) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$ref", "#/$defs/" + definitionName);
        return schema;
    }

    private ObjectNode requiredOnly(String... fields) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.set("required", array(fields));
        return schema;
    }

    private ArrayNode array(String... values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
