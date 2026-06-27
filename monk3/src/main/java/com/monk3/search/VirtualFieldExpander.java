package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.monk3.model.BooleanQueryData;
import com.monk3.model.ExactQuery;
import com.monk3.model.QueryData;
import com.monk3.model.QueryNode;
import com.monk3.model.QueryPayload;
import com.monk3.model.RangeQuery;
import com.monk3.model.TextQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jd.nomad.mapping.FieldType;
import jd.nomad.mapping.VirtualField;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class VirtualFieldExpander {
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{([a-zA-Z]+)\\}\\}");

    private final ObjectMapper objectMapper;

    public JsonNode expandAndTranslate(
            VirtualField virtualField,
            QueryData data,
            QueryParseContext context,
            SearchEngine engine
    ) {
        Map<String, JsonNode> vars = resolveSubstitutionVariables(virtualField, data);
        JsonNode substituted = substitute(virtualField.expansion(), vars);

        QueryNode expanded;
        try {
            expanded = objectMapper.convertValue(substituted, QueryNode.class);
        } catch (Exception e) {
            throw new QueryTranslationException(
                    "Failed to parse expansion for virtual field '" + virtualField.logicalName() + "': " + e.getMessage());
        }

        return expanded.translate(engine, context);
    }

    private Map<String, JsonNode> resolveSubstitutionVariables(VirtualField virtualField, QueryData data) {
        if (virtualField.type() == FieldType.PREDICATE) {
            if (data != null) {
                throw new QueryTranslationException(
                        "Predicate virtual field '" + virtualField.logicalName() + "' does not accept a data payload");
            }
            // No variables: any '{{...}}' in a predicate expansion is a config error and will
            // be rejected by substitute().
            return Map.of();
        }
        if (virtualField.type() == FieldType.SUBQUERY) {
            if (!(data instanceof BooleanQueryData booleanData)) {
                throw new QueryTranslationException(
                        "Subquery virtual field '" + virtualField.logicalName()
                                + "' requires boolean query data (an array of clause objects)");
            }
            return Map.of("data", objectMapper.valueToTree(booleanData));
        }
        if (!(data instanceof QueryPayload payload)) {
            throw new QueryTranslationException(
                    "Virtual field '" + virtualField.logicalName() + "' requires a query payload");
        }
        validatePayloadType(virtualField, payload);
        return Map.of("data", objectMapper.valueToTree(payload));
    }

    private void validatePayloadType(VirtualField virtualField, QueryPayload payload) {
        boolean compatible = switch (virtualField.type()) {
            case STRING, FREETEXT -> payload instanceof TextQuery;
            case NUMBER -> payload instanceof RangeQuery.Numeric || payload instanceof ExactQuery.Numeric;
            case DATETIME -> payload instanceof RangeQuery.Datetime || payload instanceof ExactQuery.Datetime;
            case BOOLEAN -> payload instanceof ExactQuery.BooleanValues;
            case VECTOR, SUBDOCUMENT, PREDICATE, SUBQUERY -> false;
        };
        if (!compatible) {
            throw new QueryTranslationException(
                    "Query payload type is not compatible with virtual field '"
                            + virtualField.logicalName() + "' of type '"
                            + QueryParseContext.typeName(virtualField.type()) + "'");
        }
    }

    private JsonNode substitute(JsonNode node, Map<String, JsonNode> vars) {
        if (node instanceof TextNode textNode) {
            Matcher matcher = TEMPLATE_VAR.matcher(textNode.asText());
            if (matcher.matches()) {
                String varName = matcher.group(1);
                JsonNode value = vars.get(varName);
                if (value == null) {
                    throw new QueryTranslationException(
                            "Template variable '{{" + varName + "}}' is not available for this virtual field expansion");
                }
                return value;
            }
            return node;
        }
        if (node instanceof ObjectNode objectNode) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            objectNode.properties().forEach(entry ->
                    result.set(entry.getKey(), substitute(entry.getValue(), vars)));
            return result;
        }
        if (node instanceof ArrayNode arrayNode) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            arrayNode.elements().forEachRemaining(elem -> result.add(substitute(elem, vars)));
            return result;
        }
        return node;
    }
}
