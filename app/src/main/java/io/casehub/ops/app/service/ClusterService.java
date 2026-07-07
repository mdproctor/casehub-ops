package io.casehub.ops.app.service;

import java.util.List;
import java.util.UUID;

import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.k8s.K8sClientRegistry;
import io.casehub.ops.app.model.ClusterStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ClusterService {

    @Inject
    ApplicationLifecycleService lifecycleService;

    @Inject
    K8sClientRegistry clientRegistry;

    @Transactional
    public ClusterReferenceEntity register(ClusterReferenceEntity cluster, String tenancyId) {
        cluster.tenancyId = tenancyId;
        cluster.persist();
        return cluster;
    }

    public List<ClusterReferenceEntity> list(String tenancyId) {
        return ClusterReferenceEntity.findByTenancyId(tenancyId);
    }

    public ClusterReferenceEntity findById(UUID id) {
        return ClusterReferenceEntity.findById(id);
    }

    public ClusterStatus testConnectivity(UUID clusterId) {
        return ClusterStatus.UNKNOWN;
    }

    @Transactional
    public void delete(UUID clusterId, String tenancyId) {
        if (lifecycleService.hasActiveLoopsForCluster(clusterId.toString())) {
            throw new IllegalStateException(
                    "Cannot delete cluster " + clusterId + ": active reconciliation loops exist");
        }
        ClusterReferenceEntity.deleteById(clusterId);
        clientRegistry.deregister(clusterId.toString());
    }
}
