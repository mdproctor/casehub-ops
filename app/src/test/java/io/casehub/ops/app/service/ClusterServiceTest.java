package io.casehub.ops.app.service;

import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.model.ClusterStatus;
import io.casehub.ops.app.model.ClusterType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ClusterServiceTest {

    @Inject
    ClusterService clusterService;

    @Test
    @Transactional
    void registersCluster() {
        var cluster = new ClusterReferenceEntity();
        cluster.name = "test-cluster";
        cluster.apiUrl = "https://localhost:6443";
        cluster.namespace = "default";
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = "default";

        var result = clusterService.register(cluster, "default");
        assertThat(result.id).isNotNull();
        assertThat(result.status).isEqualTo(ClusterStatus.UNKNOWN);
    }

    @Test
    @Transactional
    void listsClustersByTenancy() {
        var cluster = new ClusterReferenceEntity();
        cluster.name = "list-test";
        cluster.apiUrl = "https://localhost:6443";
        cluster.namespace = "default";
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = "list-tenant";
        clusterService.register(cluster, "list-tenant");

        var results = clusterService.list("list-tenant");
        assertThat(results).isNotEmpty();
    }

    @Test
    @Transactional
    void testConnectivityReturnsUnknown() {
        var cluster = new ClusterReferenceEntity();
        cluster.name = "connectivity-test";
        cluster.apiUrl = "https://localhost:6443";
        cluster.namespace = "default";
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = "default";
        var registered = clusterService.register(cluster, "default");

        var status = clusterService.testConnectivity(registered.id);
        assertThat(status).isEqualTo(ClusterStatus.UNKNOWN);
    }

    @Test
    @Transactional
    void deletesCluster() {
        var cluster = new ClusterReferenceEntity();
        cluster.name = "delete-test";
        cluster.apiUrl = "https://localhost:6443";
        cluster.namespace = "default";
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = "default";
        var registered = clusterService.register(cluster, "default");

        clusterService.delete(registered.id, "default");
        var found = clusterService.findById(registered.id);
        assertThat(found).isNull();
    }
}
