package com.monk.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk.model.query.QueryPayload;

import java.io.IOException;

/**
 * Parses the {@code data} object of a leaf query node into a {@link QueryPayload}. Each payload type
 * ships its own {@code @ApplicationScoped} implementation keyed by {@link #type()}; {@link QueryPayloadRegistry}
 * discovers them so new payloads register without editing {@link QueryNodeDeserializer}.
 */
public interface QueryPayloadParser {
    /** The {@code type} discriminator this parser handles (e.g. {@code "text"}). */
    String type();

    QueryPayload parse(JsonParser parser, ObjectMapper mapper, ObjectNode node) throws IOException;
}
