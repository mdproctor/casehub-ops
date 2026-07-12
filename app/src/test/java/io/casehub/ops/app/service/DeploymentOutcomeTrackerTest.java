package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Plain unit tests for DeploymentOutcomeTracker.
 *
 * <p>Tests the in-memory tracking logic: registration, convergence detection,
 * and cleanup. Does not test the {@code @Scheduled} timeout or database
 * updates — those require CDI integration.
 */
class DeploymentOutcomeTrackerTest {

    private DeploymentOutcomeTracker tracker;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        tracker = new DeploymentOutcomeTracker();
    }

    @Test
    void registersAndTracksDeployment() {
        var deploymentId = UUID.randomUUID();
        tracker.registerDeployment(deploymentId, Set.of("c1", "c2"));
        assertThat(tracker.isTracking(deploymentId)).isTrue();
    }

    @Test
    void isTrackingReturnsFalseForUnknownDeployment() {
        assertThat(tracker.isTracking(UUID.randomUUID())).isFalse();
    }

    @Test
    void marksClusterConvergedOnCleanReconciliation() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key1 = "default:" + appId + ":c1";
        var key2 = "default:" + appId + ":c2";
        // Two clusters: converging c1 keeps tracking alive so we can query it
        tracker.registerDeployment(deploymentId, Set.of("c1", "c2"));
        tracker.associateKey(deploymentId, "c1", key1);
        tracker.associateKey(deploymentId, "c2", key2);

        var event = buildCompletedEvent(key1, 0, 0, 0);
        tracker.onCloudEvent(event);

        assertThat(tracker.isConverged(deploymentId, "c1")).isTrue();
        assertThat(tracker.isConverged(deploymentId, "c2")).isFalse();
    }

    @Test
    void singleClusterConvergenceRemovesTracking() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", key);

        tracker.onCloudEvent(buildCompletedEvent(key, 0, 0, 0));

        // Single cluster — convergence removes tracking immediately
        assertThat(tracker.isTracking(deploymentId)).isFalse();
    }

    @Test
    void doesNotConvergeOnFaults() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", key);

        var event = buildCompletedEvent(key, 0, 0, 1);
        tracker.onCloudEvent(event);

        assertThat(tracker.isConverged(deploymentId, "c1")).isFalse();
    }

    @Test
    void doesNotConvergeWhenAdditionsRemain() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", key);

        var event = buildCompletedEvent(key, 2, 0, 0);
        tracker.onCloudEvent(event);

        assertThat(tracker.isConverged(deploymentId, "c1")).isFalse();
    }

    @Test
    void allClustersConvergedRemovesTracking() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key1 = "default:" + appId + ":c1";
        var key2 = "default:" + appId + ":c2";

        tracker.registerDeployment(deploymentId, Set.of("c1", "c2"));
        tracker.associateKey(deploymentId, "c1", key1);
        tracker.associateKey(deploymentId, "c2", key2);

        tracker.onCloudEvent(buildCompletedEvent(key1, 0, 0, 0));
        // Still tracking — c2 not converged yet
        assertThat(tracker.isTracking(deploymentId)).isTrue();

        tracker.onCloudEvent(buildCompletedEvent(key2, 0, 0, 0));
        // All converged — tracking entry removed
        assertThat(tracker.isTracking(deploymentId)).isFalse();
    }

    @Test
    void ignoresEventsForUntrackedKeys() {
        var event = buildCompletedEvent("unknown:key:cluster", 0, 0, 0);
        // Should not throw
        assertThatNoException().isThrownBy(() -> tracker.onCloudEvent(event));
    }

    @Test
    void ignoresNonReconciliationCompletedEvents() {
        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("urn:io.casehub:desiredstate"))
                .withType("io.casehub.desiredstate.node.faulted")
                .withTime(OffsetDateTime.now())
                .build();

        // Should not throw
        assertThatNoException().isThrownBy(() -> tracker.onCloudEvent(event));
    }

    @Test
    void convergenceIsIdempotent() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", key);

        var event = buildCompletedEvent(key, 0, 0, 0);
        tracker.onCloudEvent(event);
        // Second event for same key — should not error
        assertThatNoException().isThrownBy(() -> tracker.onCloudEvent(event));
    }

    @Test
    void cleanupStalePendingRemovesTimedOutDeployments() {
        var deploymentId = UUID.randomUUID();
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", "key:c1");

        tracker.cleanupStalePending(Instant.now().plus(java.time.Duration.ofMinutes(11)));

        assertThat(tracker.isTracking(deploymentId)).isFalse();
    }

    @Test
    void cleanupStalePendingKeepsRecentDeployments() {
        var deploymentId = UUID.randomUUID();
        tracker.registerDeployment(deploymentId, Set.of("c1"));

        tracker.cleanupStalePending(Instant.now().plus(java.time.Duration.ofMinutes(5)));

        assertThat(tracker.isTracking(deploymentId)).isTrue();
    }


    private CloudEvent buildCompletedEvent(String tenancyId,
                                            int additionsCount,
                                            int removalsCount,
                                            int faultCount) {
        var data = new ReconciliationCompletedData(
                tenancyId, 1, 3, additionsCount, removalsCount, faultCount, Instant.now());
        try {
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("urn:io.casehub:desiredstate"))
                    .withType(DesiredStateEventTypes.RECONCILIATION_COMPLETED)
                    .withTime(OffsetDateTime.now())
                    .withData("application/json", MAPPER.writeValueAsBytes(data))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
