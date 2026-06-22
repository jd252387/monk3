package com.monk3.api;

import com.monk3.search.SearchExecutionException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SearchExecutionExceptionMapper implements ExceptionMapper<SearchExecutionException> {
    @Override
    public Response toResponse(SearchExecutionException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.of("search_execution_failed", exception.getMessage()))
                .build();
    }
}
