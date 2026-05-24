package com.monk3.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

@Provider
@Priority(Priorities.USER)
public class InvalidJsonRequestExceptionMapper implements ExceptionMapper<MismatchedInputException> {
    private static final String CODE = "invalid_query_structure";

    @Override
    public Response toResponse(MismatchedInputException exception) {
        return responseFor(exception);
    }

    static Response responseFor(JsonMappingException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.of(CODE, explanation(exception)))
                .build();
    }

    private static String explanation(JsonMappingException exception) {
        String location = formatPath(exception);
        String message = switch (exception) {
            case UnrecognizedPropertyException unrecognized -> unrecognizedPropertyMessage(unrecognized);
            case InvalidTypeIdException invalidType -> invalidTypeMessage(invalidType);
            default -> exception.getOriginalMessage();
        };
        if (location.isBlank()) {
            return message;
        }
        return "Invalid query structure at '" + location + "': " + message;
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
                + "'. Supported query data types are 'text' and 'range'.";
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

    private static String formatPath(JsonMappingException exception) {
        StringBuilder builder = new StringBuilder();
        for (JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) {
                if (!builder.isEmpty()) {
                    builder.append(".");
                }
                builder.append(reference.getFieldName());
            }
            if (reference.getIndex() >= 0) {
                builder.append("[");
                builder.append(reference.getIndex());
                builder.append("]");
            }
        }
        return builder.toString();
    }
}
