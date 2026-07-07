package io.casehub.ops.app.rest;

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
@Path("/api/applications/{id}/services/{serviceId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServiceOperationResource {

    @GET
    @Path("/status")
    public Response getStatus(@PathParam("id") UUID id,
                              @PathParam("serviceId") String serviceId) {
        // Phase 1: stubbed
        return Response.ok().build();
    }

    @POST
    @Path("/scale")
    public Response scale(@PathParam("id") UUID id,
                          @PathParam("serviceId") String serviceId) {
        // Phase 1: stubbed
        return Response.accepted().build();
    }

    @POST
    @Path("/upgrade")
    public Response upgrade(@PathParam("id") UUID id,
                            @PathParam("serviceId") String serviceId) {
        // Phase 1: stubbed
        return Response.accepted().build();
    }
}
