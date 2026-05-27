package com.monk3.api;

import com.monk3.model.BackendQuery;
import com.monk3.model.SearchExecutionRequest;
import com.monk3.model.SearchExecutionResponse;
import com.monk3.model.SearchQueryRequest;
import com.monk3.search.QueryTranslationService;
import com.monk3.search.SearchExecutionService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.util.List;

@Path("/queries")
@RunOnVirtualThread
@RequiredArgsConstructor
public class QueryResource {
    private static final String SCHEMA_MEDIA_TYPE = "application/schema+json";

    private final QueryTranslationService queryTranslationService;
    private final SearchExecutionService searchExecutionService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SearchQueryRequest validateQuery(@Valid SearchQueryRequest request) {
        return request;
    }

    @POST
    @Path("/parse")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<BackendQuery> parseQuery(@Valid SearchQueryRequest request) {
        return queryTranslationService.translateByBackend(request);
    }

    @POST
    @Path("/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SearchExecutionResponse search(@Valid SearchExecutionRequest request) {
        return searchExecutionService.search(request);
    }

    @GET
    @Path("/schema")
    @Produces(SCHEMA_MEDIA_TYPE)
    public Response querySchema() {
        InputStream inputStream = QueryResource.class
                .getClassLoader()
                .getResourceAsStream("search-query-dsl.schema.json");
        if (inputStream == null) {
            throw new IllegalStateException("search-query-dsl.schema.json was not found on the classpath");
        }
        
        return Response.ok(inputStream, SCHEMA_MEDIA_TYPE).build();
    }
}
