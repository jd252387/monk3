package com.monk3.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.model.RangeBound;

import java.io.IOException;
import java.math.BigDecimal;

public class RangeBoundDeserializer extends JsonDeserializer<RangeBound> {
    @Override
    public RangeBound deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.readValueAsTree();
        if (node.isTextual()) {
            return new RangeBound(node.textValue());
        }
        if (node.isInt() || node.isLong()) {
            return new RangeBound(node.longValue());
        }
        if (node.isBigInteger()) {
            return new RangeBound(node.bigIntegerValue());
        }
        if (node.isBigDecimal()) {
            return new RangeBound(node.decimalValue());
        }
        if (node.isNumber()) {
            return new RangeBound(new BigDecimal(node.asText()));
        }
        return (RangeBound) context.handleUnexpectedToken(RangeBound.class, parser);
    }
}
