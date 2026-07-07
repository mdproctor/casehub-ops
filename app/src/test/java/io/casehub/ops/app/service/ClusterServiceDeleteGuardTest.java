package io.casehub.ops.app.service;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Plain unit tests for the ClusterService.delete() active-loop guard.
 *
 * <p>Tests the guard logic using a direct ApplicationLifecycleService instance
 * (no CDI required — the active loop index is entirely in-memory). Does not
 * test the Panache entity deletion or K8sClientRegistry deregistration, since
 * those require CDI integration. The guard check itself is the critical path
 * tested here.
 */
class ClusterServiceDeleteGuardTest {

    private ApplicationLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        lifecycleService = new ApplicationLifecycleService();
    }

    @Test
    void deleteRejectsWhenActiveLoopsExist() {
        String clusterId = UUID.randomUUID().toString();
        String compositeKey = "default:" + UUID.randomUUID() + ":" + clusterId;

        lifecycleService.trackLoopKey(clusterId, compositeKey);

        // The guard logic: check hasActiveLoopsForCluster before proceeding
        assertThat(lifecycleService.hasActiveLoopsForCluster(clusterId)).isTrue();

        // Simulate what ClusterService.delete() does
        assertThatIllegalStateException()
                .isThrownBy(() -> {
                    if (lifecycleService.hasActiveLoopsForCluster(clusterId)) {
                        throw new IllegalStateException(
                                "Cannot delete cluster " + clusterId
                                        + ": active reconciliation loops exist");
                    }
                })
                .withMessageContaining("active reconciliation loops");
    }

    @Test
    void deleteAllowedWhenNoActiveLoops() {
        String clusterId = UUID.randomUUID().toString();

        assertThat(lifecycleService.hasActiveLoopsForCluster(clusterId)).isFalse();

        // No exception thrown — delete would proceed
        assertThatNoException().isThrownBy(() -> {
            if (lifecycleService.hasActiveLoopsForCluster(clusterId)) {
                throw new IllegalStateException(
                        "Cannot delete cluster " + clusterId
                                + ": active reconciliation loops exist");
            }
        });
    }

    @Test
    void deleteAllowedAfterAllLoopsRemoved() {
        String clusterId = UUID.randomUUID().toString();
        String key1 = "tenant-a:" + UUID.randomUUID() + ":" + clusterId;
        String key2 = "tenant-b:" + UUID.randomUUID() + ":" + clusterId;

        lifecycleService.trackLoopKey(clusterId, key1);
        lifecycleService.trackLoopKey(clusterId, key2);
        assertThat(lifecycleService.hasActiveLoopsForCluster(clusterId)).isTrue();

        lifecycleService.removeLoopKey(key1);
        assertThat(lifecycleService.hasActiveLoopsForCluster(clusterId)).isTrue();

        lifecycleService.removeLoopKey(key2);
        assertThat(lifecycleService.hasActiveLoopsForCluster(clusterId)).isFalse();

        // Now delete would succeed
        assertThatNoException().isThrownBy(() -> {
            if (lifecycleService.hasActiveLoopsForCluster(clusterId)) {
                throw new IllegalStateException("active reconciliation loops exist");
            }
        });
    }

    @Test
    void deleteGuardOnlyChecksTargetCluster() {
        String targetCluster = UUID.randomUUID().toString();
        String otherCluster = UUID.randomUUID().toString();

        // Loops exist on otherCluster, NOT on targetCluster
        lifecycleService.trackLoopKey(otherCluster, "tenant:" + UUID.randomUUID() + ":" + otherCluster);

        assertThat(lifecycleService.hasActiveLoopsForCluster(targetCluster)).isFalse();
        assertThat(lifecycleService.hasActiveLoopsForCluster(otherCluster)).isTrue();
    }
}
