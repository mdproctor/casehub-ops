package io.casehub.ops.app.rest;

import java.util.List;
import java.util.UUID;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Blocking
@ApplicationScoped
@Path("/api/applications/{id}/cases")
@Produces(MediaType.APPLICATION_JSON)
public class CaseResource {

    @GET
    public Response listCases(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.ok(List.of()).build();
    }

    @GET
    @Path("/{caseId}")
    public Response getCase(@PathParam("id") UUID id,
                            @PathParam("caseId") UUID caseId) {
        // Phase 1: stubbed
        return Response.ok().build();
    }

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Response streamEvents(@PathParam("id") UUID id) {
        // Phase 1: stubbed SSE — immediate close
        return Response.ok().build();
    }
}
