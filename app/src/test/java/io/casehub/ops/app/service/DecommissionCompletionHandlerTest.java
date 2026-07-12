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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Plain unit tests for DecommissionCompletionHandler.
 *
 * <p>Uses recording stubs for ReconciliationLoop and ApplicationLifecycleService
 * to verify the handler correctly stops loops, removes tracking keys, and
 * manages its internal state on convergence and cancellation.
 */
class DecommissionCompletionHandlerTest {

    private DecommissionCompletionHandler handler;
    private RecordingLoopStopper loopStopper;
    private RecordingKeyRemover keyRemover;
    private RecordingStatusTransitioner statusTransitioner;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        loopStopper = new RecordingLoopStopper();
        keyRemover = new RecordingKeyRemover();
        statusTransitioner = new RecordingStatusTransitioner();
        handler = new DecommissionCompletionHandler(loopStopper::stop, keyRemover::remove,
                statusTransitioner::transition);
    }

    @Test
    void registersDecommission() {
        var appId = UUID.randomUUID();
        handler.registerDecommission(appId, Set.of("key1", "key2"));
        assertThat(handler.isTracking(appId)).isTrue();
    }

    @Test
    void isTrackingReturnsFalseForUnknownApp() {
        assertThat(handler.isTracking(UUID.randomUUID())).isFalse();
    }

    @Test
    void cancelDecommissionRemovesTracking() {
        var appId = UUID.randomUUID();
        handler.registerDecommission(appId, Set.of("key1"));
        handler.cancelDecommission(appId);
        assertThat(handler.isTracking(appId)).isFalse();
    }

    @Test
    void cancelDecommissionIsNoOpForUnknownApp() {
        assertThatNoException().isThrownBy(
                () -> handler.cancelDecommission(UUID.randomUUID()));
    }

    @Test
    void convergenceStopsLoopAndRemovesKey() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        handler.onKeyConverged(key);

        assertThat(loopStopper.stopped).containsExactly(key);
        assertThat(keyRemover.removed).containsExactly(key);
    }

    @Test
    void allKeysConvergedRemovesTracking() {
        var appId = UUID.randomUUID();
        var key1 = "default:" + appId + ":c1";
        var key2 = "default:" + appId + ":c2";
        handler.registerDecommission(appId, Set.of(key1, key2));

        handler.onKeyConverged(key1);
        assertThat(handler.isTracking(appId)).isTrue();

        handler.onKeyConverged(key2);
        assertThat(handler.isTracking(appId)).isFalse();
    }

    @Test
    void allKeysConvergedTransitionsToDecommissioned() {
        var appId = UUID.randomUUID();
        var key1 = "default:" + appId + ":c1";
        var key2 = "default:" + appId + ":c2";
        handler.registerDecommission(appId, Set.of(key1, key2));

        handler.onKeyConverged(key1);
        assertThat(statusTransitioner.transitioned).isEmpty();

        handler.onKeyConverged(key2);
        assertThat(statusTransitioner.transitioned).containsExactly(appId);
    }

    @Test
    void singleKeyConvergenceTransitionsToDecommissioned() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        handler.onKeyConverged(key);
        assertThat(statusTransitioner.transitioned).containsExactly(appId);
    }

    @Test
    void cloudEventTriggersConvergenceOnCleanReconciliation() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        // removalsCount=0, faultCount=0 => converged (empty graph fully deprovisioned)
        var event = buildCompletedEvent(key, 0, 0, 0);
        handler.onCloudEvent(event);

        assertThat(loopStopper.stopped).containsExactly(key);
        assertThat(keyRemover.removed).containsExactly(key);
        assertThat(handler.isTracking(appId)).isFalse();
    }

    @Test
    void cloudEventDoesNotConvergeWhenRemovalsRemain() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        // removalsCount=3 => still deprovisioning
        var event = buildCompletedEvent(key, 0, 3, 0);
        handler.onCloudEvent(event);

        assertThat(loopStopper.stopped).isEmpty();
        assertThat(keyRemover.removed).isEmpty();
        assertThat(handler.isTracking(appId)).isTrue();
    }

    @Test
    void cloudEventDoesNotConvergeOnFaults() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        // faultCount=1 => deprovisioning has faults
        var event = buildCompletedEvent(key, 0, 0, 1);
        handler.onCloudEvent(event);

        assertThat(loopStopper.stopped).isEmpty();
        assertThat(handler.isTracking(appId)).isTrue();
    }

    @Test
    void ignoresEventsForUntrackedKeys() {
        var event = buildCompletedEvent("unknown:key:cluster", 0, 0, 0);
        assertThatNoException().isThrownBy(() -> handler.onCloudEvent(event));
        assertThat(loopStopper.stopped).isEmpty();
    }

    @Test
    void ignoresNonReconciliationCompletedEvents() {
        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("urn:io.casehub:desiredstate"))
                .withType("io.casehub.desiredstate.node.faulted")
                .withTime(OffsetDateTime.now())
                .build();

        assertThatNoException().isThrownBy(() -> handler.onCloudEvent(event));
        assertThat(loopStopper.stopped).isEmpty();
    }

    @Test
    void convergenceIsIdempotent() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        handler.onKeyConverged(key);
        // Second call — already stopped, should not throw
        assertThatNoException().isThrownBy(() -> handler.onKeyConverged(key));
    }

    @Test
    void cleanupStaleDecommissionsForceStopsTimedOutApps() {
        var appId = UUID.randomUUID();
        var key1  = "default:" + appId + ":c1";
        var key2  = "default:" + appId + ":c2";
        handler.registerDecommission(appId, Set.of(key1, key2));

        handler.cleanupStaleDecommissions(Instant.now().plus(java.time.Duration.ofMinutes(11)));

        assertThat(handler.isTracking(appId)).isFalse();
        assertThat(loopStopper.stopped).containsExactlyInAnyOrder(key1, key2);
        assertThat(keyRemover.removed).containsExactlyInAnyOrder(key1, key2);
        assertThat(statusTransitioner.transitioned).containsExactly(appId);
    }

    @Test
    void cleanupStaleDecommissionsKeepsRecentApps() {
        var appId = UUID.randomUUID();
        handler.registerDecommission(appId, Set.of("key1"));

        handler.cleanupStaleDecommissions(Instant.now().plus(java.time.Duration.ofMinutes(5)));

        assertThat(handler.isTracking(appId)).isTrue();
        assertThat(loopStopper.stopped).isEmpty();
    }


    // --- Recording stubs ---

    private static class RecordingLoopStopper {
        final List<String> stopped = new ArrayList<>();

        void stop(String key) {
            stopped.add(key);
        }
    }

    private static class RecordingKeyRemover {
        final List<String> removed = new ArrayList<>();

        void remove(String key) {
            removed.add(key);
        }
    }

    private static class RecordingStatusTransitioner {
        final List<UUID> transitioned = new ArrayList<>();

        void transition(UUID appId) {
            transitioned.add(appId);
        }
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
