package com.monk.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.QueryPayload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps a query payload's {@code type} discriminator to the {@link QueryPayloadParser} bean that handles it.
 * Parsers register themselves as {@code @ApplicationScoped} beans, so adding a payload type does not touch
 * {@link QueryNodeDeserializer}.
 */
@ApplicationScoped
public class QueryPayloadRegistry {
    private final Map<String, QueryPayloadParser> byType;

    @Inject
    QueryPayloadRegistry(Instance<QueryPayloadParser> parsers) {
        this.byType = parsers.stream().collect(Collectors.toMap(
                QueryPayloadParser::type,
                parser -> parser,
                (first, second) -> {
                    throw new IllegalStateException(
                            "Duplicate QueryPayloadParser for type '" + first.type() + "'");
                }));
    }

    public QueryPayload parse(JsonParser parser, ObjectMapper mapper, ObjectNode node, String type)
            throws IOException {
        QueryPayloadParser payloadParser = byType.get(type);
        if (payloadParser == null) {
            throw MismatchedInputException.from(parser, Object.class, unsupportedTypeMessage(type));
        }
        return payloadParser.parse(parser, mapper, node);
    }

    private String unsupportedTypeMessage(String type) {
        String supported = byType.keySet().stream()
                .sorted()
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(", "));
        return "Unsupported query data type '" + type + "'. Supported query data types are " + supported + ".";
    }
}
