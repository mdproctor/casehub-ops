package io.casehub.ops.app.rest;

import java.util.UUID;

import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.rest.dto.RegisterClusterRequest;
import io.casehub.ops.app.service.ClusterService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
@Path("/api/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource {

    @Inject
    ClusterService clusterService;

    @POST
    public Response register(RegisterClusterRequest request,
                             @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);

        var cluster = new ClusterReferenceEntity();
        cluster.name = request.name();
        cluster.apiUrl = request.apiUrl();
        cluster.namespace = request.namespace();
        cluster.credentialRef = request.credentialRef();
        cluster.clusterType = request.clusterType();

        var registered = clusterService.register(cluster, tenancyId);
        return Response.status(Response.Status.CREATED).entity(registered).build();
    }

    @GET
    public Response list(@Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        return Response.ok(clusterService.list(tenancyId)).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") UUID id) {
        var cluster = clusterService.findById(id);
        if (cluster == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(cluster).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") UUID id,
                           @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        clusterService.delete(id, tenancyId);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/test")
    public Response testConnectivity(@PathParam("id") UUID id) {
        var status = clusterService.testConnectivity(id);
        return Response.ok().entity(status).build();
    }
}
