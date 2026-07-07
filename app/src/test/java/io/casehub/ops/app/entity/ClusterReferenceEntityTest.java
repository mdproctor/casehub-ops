package io.casehub.ops.app.entity;

import io.casehub.ops.app.model.ClusterType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ClusterReferenceEntityTest {

    @Test
    @Transactional
    void persistsAndFindsCluster() {
        var cluster = new ClusterReferenceEntity();
        cluster.name = "ops-prod";
        cluster.apiUrl = "https://k8s.example.com:6443";
        cluster.namespace = "casehub";
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = "default";
        cluster.persist();

        assertThat(cluster.id).isNotNull();
        var results = ClusterReferenceEntity.findByTenancyId("default");
        assertThat(results).isNotEmpty();
    }
}
