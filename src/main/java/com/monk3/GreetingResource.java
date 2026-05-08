package com.monk3;

import com.monk3.model.SearchQueryRequest;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.validation.Valid;

@Path("/queries")
public class GreetingResource {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SearchQueryRequest createQuery(@Valid SearchQueryRequest request) {
        return request;
    }
}
