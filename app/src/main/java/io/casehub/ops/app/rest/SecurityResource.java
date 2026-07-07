package io.casehub.ops.app.rest;

import java.util.List;
import java.util.UUID;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Blocking
@ApplicationScoped
@Path("/api/applications/{id}/security")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecurityResource {

    @GET
    @Path("/cves")
    public Response getCves(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.ok(List.of()).build();
    }

    @POST
    @Path("/cves")
    public Response scanCves(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.accepted().build();
    }

    @GET
    @Path("/posture")
    public Response getPosture(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.ok().build();
    }
}
