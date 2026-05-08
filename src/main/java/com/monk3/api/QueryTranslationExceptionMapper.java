package com.monk3.api;

import com.monk3.search.QueryTranslationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class QueryTranslationExceptionMapper implements ExceptionMapper<QueryTranslationException> {
    @Override
    public Response toResponse(QueryTranslationException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.of("query_translation_failed", exception.getMessage()))
                .build();
    }
}
