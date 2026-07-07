package io.casehub.ops.app.rest;

import java.util.List;
import java.util.UUID;

import io.casehub.ops.app.rest.dto.DeployRequest;
import io.casehub.ops.app.service.ApplicationLifecycleService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Blocking
@ApplicationScoped
@Path("/api/applications/{id}/deployments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeploymentResource {

    @Inject
    ApplicationLifecycleService lifecycleService;

    @POST
    public Response deploy(@PathParam("id") UUID id,
                           DeployRequest request,
                           @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        lifecycleService.deploy(id, tenancyId);
        return Response.accepted().build();
    }

    @GET
    public Response listDeployments(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.ok(List.of()).build();
    }

    @GET
    @Path("/current")
    public Response getCurrentDeployment(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.ok().build();
    }

    @POST
    @Path("/rollback")
    public Response rollback(@PathParam("id") UUID id) {
        // Phase 1: stubbed
        return Response.accepted().build();
    }
}
