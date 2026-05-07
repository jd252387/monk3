package com.monk3;

import com.fasterxml.jackson.databind.JsonNode;
import com.monk3.model.SearchQueryRequest;
import com.monk3.schema.SearchQuerySchemaService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.validation.Valid;

@Path("/queries")
public class GreetingResource {
    private static final String APPLICATION_SCHEMA_JSON = "application/schema+json";

    private final SearchQuerySchemaService schemaService;

    public GreetingResource(SearchQuerySchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SearchQueryRequest createQuery(@Valid SearchQueryRequest request) {
        return request;
    }

    @GET
    @Path("/schema")
    @Produces(APPLICATION_SCHEMA_JSON)
    public JsonNode schema() {
        return schemaService.generateSchema();
    }
}
