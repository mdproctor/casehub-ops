package io.casehub.ops.app.service;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeConvergenceTrackerTest {

    private NodeConvergenceTracker tracker;
    private CopyOnWriteArrayList<SignalRecord> signals;

    @BeforeEach
    void setUp() {
        signals = new CopyOnWriteArrayList<>();
        tracker = new NodeConvergenceTracker(
                (caseId, path, value) -> signals.add(new SignalRecord(caseId, path, value)),
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void signalsConvergedWhenAllNodesRecovered() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", "converged");

        tracker.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(caseId);
        assertThat(signals.get(0).path()).isEqualTo("scalingStatus");
        assertThat(signals.get(0).value()).isEqualTo("converged");
    }

    @Test
    void doesNotSignalUntilAllNodesRecovered() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1", "node-2"),
                "scalingStatus", "converged");

        tracker.onCloudEvent(recoveredEvent("node-1"));
        assertThat(signals).isEmpty();

        tracker.onCloudEvent(recoveredEvent("node-2"));
        assertThat(signals).hasSize(1);
    }

    @Test
    void ignoresEventsForUnregisteredCases() {
        tracker.onCloudEvent(recoveredEvent("unknown-node"));
        assertThat(signals).isEmpty();
    }

    @Test
    void tracksMultipleCasesWithDifferentSignalPaths() {
        UUID driftCase = UUID.randomUUID();
        UUID scalingCase = UUID.randomUUID();
        tracker.register(driftCase, Set.of("node-d"),
                "remediationStatus", "converged");
        tracker.register(scalingCase, Set.of("node-s"),
                "scalingStatus", "converged");

        tracker.onCloudEvent(recoveredEvent("node-d"));
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).path()).isEqualTo("remediationStatus");

        tracker.onCloudEvent(recoveredEvent("node-s"));
        assertThat(signals).hasSize(2);
        assertThat(signals.get(1).path()).isEqualTo("scalingStatus");
    }

    @Test
    void convergenceDeregistersCase() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", "converged");

        tracker.onCloudEvent(recoveredEvent("node-1"));

        assertThat(tracker.isTracking(caseId)).isFalse();
    }

    @Test
    void duplicateRecoveryEventIgnored() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", "converged");

        tracker.onCloudEvent(recoveredEvent("node-1"));
        tracker.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
    }

    @Test
    void nonRecoveredEventIgnored() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", "converged");

        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("/test"))
                .withType(DesiredStateEventTypes.NODE_FAULTED)
                .withData("application/json", "{}".getBytes())
                .build();
        tracker.onCloudEvent(event);

        assertThat(signals).isEmpty();
    }

    private CloudEvent recoveredEvent(String nodeId) {
        try {
            var data = new NodeRecoveredData("tenant:app:cluster", nodeId, "K8S_DEPLOYMENT", 1, null);
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("/reconciliation"))
                    .withType(DesiredStateEventTypes.NODE_RECOVERED)
                    .withData("application/json", mapper.writeValueAsBytes(data))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record SignalRecord(UUID caseId, String path, Object value) {}
}
