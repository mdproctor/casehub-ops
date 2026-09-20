package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.ClusterResource;
import io.casehub.ops.app.rest.dto.RegisterClusterRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/clusters", basePath = "/api/ops/clusters")
@ApplicationScoped
public class OpsClusterApi {

    @Inject ClusterResource resource;

    @PlatformMutation("Register a cluster")
    @RestPath("/")
    public Object registerCluster(RegisterClusterRequest request) {
        return resource.register(request, null).getEntity();
    }

    @PlatformQuery("List clusters")
    @RestPath("/")
    public Object listClusters() {
        return resource.list(null).getEntity();
    }

    @PlatformQuery("Get cluster details")
    @RestPath("/{id}")
    public Object getCluster(@PathParam UUID id) {
        return resource.get(id).getEntity();
    }

    @PlatformMutation("Delete a cluster")
    @RestPath("/{id}/delete")
    public Object deleteCluster(@PathParam UUID id) {
        return resource.delete(id, null).getEntity();
    }

    @PlatformMutation("Test cluster connectivity")
    @RestPath("/{id}/test")
    public Object testConnectivity(@PathParam UUID id) {
        return resource.testConnectivity(id).getEntity();
    }
}
