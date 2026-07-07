package io.casehub.ops.app.rest;

import java.util.UUID;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Blocking
@ApplicationScoped
@Path("/api/applications/{id}/reconciliation")
@Produces(MediaType.APPLICATION_JSON)
public class ReconciliationResource {

    @GET
    @Path("/status")
    public Response getStatus(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.ok().build();
    }

    @POST
    @Path("/trigger")
    public Response trigger(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.accepted().build();
    }

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Response streamEvents(@PathParam("id") UUID id) {
        // Phase 1: stubbed SSE — immediate close
        return Response.ok().build();
    }
}
