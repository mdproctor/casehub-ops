package io.casehub.ops.app.api;

import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.model.ClusterStatus;
import io.casehub.ops.app.rest.dto.RegisterClusterRequest;
import io.casehub.ops.app.service.ClusterService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "ops/clusters", basePath = "/api/ops/clusters")
@ApplicationScoped
public class OpsClusterApi {

    @Inject ClusterService clusterService;

    @PlatformMutation("Register a cluster")
    @RestPath("/")
    public ClusterReferenceEntity registerCluster(RegisterClusterRequest request) {
        var cluster = new ClusterReferenceEntity();
        cluster.name = request.name();
        cluster.apiUrl = request.apiUrl();
        cluster.namespace = request.namespace();
        cluster.credentialRef = request.credentialRef();
        cluster.clusterType = request.clusterType();
        return clusterService.register(cluster, null);
    }

    @PlatformQuery("List clusters")
    @RestPath("/")
    public List<ClusterReferenceEntity> listClusters() {
        return clusterService.list(null);
    }

    @PlatformQuery("Get cluster details")
    @RestPath("/{id}")
    public ClusterReferenceEntity getCluster(@PathParam UUID id) {
        return clusterService.findById(id);
    }

    @PlatformMutation("Delete a cluster")
    @RestPath("/{id}/delete")
    public void deleteCluster(@PathParam UUID id) {
        var cluster = clusterService.findById(id);
        if (cluster != null) {
            clusterService.delete(id, cluster.tenancyId);
        }
    }

    @PlatformMutation("Test cluster connectivity")
    @RestPath("/{id}/test")
    public ClusterStatus testConnectivity(@PathParam UUID id) {
        return clusterService.testConnectivity(id);
    }
}
