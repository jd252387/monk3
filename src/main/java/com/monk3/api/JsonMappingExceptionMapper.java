package com.monk3.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
@Priority(Priorities.USER)
public class JsonMappingExceptionMapper implements ExceptionMapper<JsonMappingException> {
    static final String CODE = "invalid_query_structure";

    @Override
    public Response toResponse(JsonMappingException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.of(CODE, explanation(exception)))
                .build();
    }

    static String explanation(JsonMappingException exception) {
        String message = switch (exception) {
            case UnrecognizedPropertyException unrecognized -> unrecognizedPropertyMessage(unrecognized);
            case InvalidTypeIdException invalidType -> invalidTypeMessage(invalidType);
            default -> exception.getOriginalMessage();
        };

        return "Invalid query structure at '" + path(exception) + "': " + message;
    }

    private static String path(JsonMappingException exception) {
        return exception.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? reference.getFieldName()
                        : "[" + reference.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String unrecognizedPropertyMessage(UnrecognizedPropertyException exception) {
        String allowedProperties = allowedProperties(exception.getKnownPropertyIds());
        String message = "property '" + exception.getPropertyName() + "' is not part of the query DSL";
        if (!allowedProperties.isBlank()) {
            message += ". Allowed properties here are: " + allowedProperties;
        }
        return message + ".";
    }

    private static String invalidTypeMessage(InvalidTypeIdException exception) {
        return "unsupported query data type '" + exception.getTypeId()
                + "'. Supported query data types are 'text', 'range', and 'exact'.";
    }

    private static String allowedProperties(Collection<Object> knownPropertyIds) {
        if (knownPropertyIds == null || knownPropertyIds.isEmpty()) {
            return "";
        }
        
        return knownPropertyIds.stream()
                .map(Object::toString)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(", "));
    }
}
