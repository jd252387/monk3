package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.monk3.model.TextQuery.CapsulePhrase;
import com.monk3.model.TextQuery.Phrase;
import com.monk3.model.TextQuery.StandardPhrase;

import java.io.IOException;
import java.util.Set;

/**
 * Deserializes a {@link Phrase} from its JSON object, dispatching on the required {@code type}
 * discriminator. Only object phrases are accepted (no bare strings).
 */
public class PhraseDeserializer extends JsonDeserializer<Phrase> {
    private static final Set<String> STANDARD_FIELDS = Set.of("type", "value", "isExact");
    private static final Set<String> CAPSULE_FIELDS = Set.of("type", "value");

    @Override
    public Phrase deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);
        if (!node.isObject()) {
            throw MismatchedInputException.from(parser, Phrase.class, "Phrase must be an object");
        }

        JsonNode typeNode = node.get("type");
        if (typeNode == null || typeNode.isNull()) {
            throw MismatchedInputException.from(parser, Phrase.class, "Phrase type is required");
        }
        if (!typeNode.isTextual()) {
            throw MismatchedInputException.from(parser, Phrase.class, "Phrase type must be a string");
        }

        return switch (typeNode.textValue()) {
            case "phrase" -> readStandard(parser, node);
            case "capsule" -> readCapsule(parser, node);
            default -> throw MismatchedInputException.from(parser, Phrase.class, unsupportedTypeMessage(typeNode.textValue()));
        };
    }

    private static StandardPhrase readStandard(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, STANDARD_FIELDS);
        String value = requireValue(parser, node);
        JsonNode isExactNode = node.get("isExact");
        Boolean isExact = null;
        if (isExactNode != null && !isExactNode.isNull()) {
            if (!isExactNode.isBoolean()) {
                throw MismatchedInputException.from(parser, Phrase.class, "Phrase isExact must be a boolean");
            }
            isExact = isExactNode.booleanValue();
        }
        return new StandardPhrase(value, isExact);
    }

    private static CapsulePhrase readCapsule(JsonParser parser, JsonNode node) throws JsonMappingException {
        rejectUnknownFields(parser, node, CAPSULE_FIELDS);
        return new CapsulePhrase(requireValue(parser, node));
    }

    private static String requireValue(JsonParser parser, JsonNode node) throws JsonMappingException {
        JsonNode valueNode = node.get("value");
        if (valueNode == null || !valueNode.isTextual() || valueNode.textValue().isBlank()) {
            throw MismatchedInputException.from(parser, Phrase.class, "Phrase value must be a non-blank string");
        }
        return valueNode.textValue();
    }

    private static String unsupportedTypeMessage(String type) {
        return "Unsupported phrase type '" + type + "'. Supported phrase types are 'phrase' and 'capsule'.";
    }

    private static void rejectUnknownFields(JsonParser parser, JsonNode node, Set<String> allowed)
            throws JsonMappingException {
        var fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!allowed.contains(fieldName)) {
                throw MismatchedInputException.from(parser, Phrase.class, "Unknown phrase property: " + fieldName);
            }
        }
    }
}
