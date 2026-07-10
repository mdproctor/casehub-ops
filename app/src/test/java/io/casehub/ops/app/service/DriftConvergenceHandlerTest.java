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

class DriftConvergenceHandlerTest {

    private DriftConvergenceHandler handler;
    private CopyOnWriteArrayList<SignalRecord> signals;

    @BeforeEach
    void setUp() {
        signals = new CopyOnWriteArrayList<>();
        handler = new DriftConvergenceHandler(
                (caseId, path, value) -> signals.add(new SignalRecord(caseId, path, value)),
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void singleNodeRecoverySignalsConvergence() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(caseId);
        assertThat(signals.get(0).path()).isEqualTo("remediationStatus");
    }

    @Test
    void multiNodeRequiresAllRecovered() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1", "node-2"));

        handler.onCloudEvent(recoveredEvent("node-1"));
        assertThat(signals).isEmpty();

        handler.onCloudEvent(recoveredEvent("node-2"));
        assertThat(signals).hasSize(1);
    }

    @Test
    void untrackedNodeIgnored() {
        handler.onCloudEvent(recoveredEvent("unknown-node"));
        assertThat(signals).isEmpty();
    }

    @Test
    void nonRecoveredEventIgnored() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("/test"))
                .withType(DesiredStateEventTypes.NODE_FAULTED)
                .withData("application/json", "{}".getBytes())
                .build();
        handler.onCloudEvent(event);

        assertThat(signals).isEmpty();
    }

    @Test
    void multipleCasesTrackedIndependently() {
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        handler.registerDriftCase(case1, Set.of("node-1"));
        handler.registerDriftCase(case2, Set.of("node-2"));

        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(case1);
        assertThat(handler.isTracking(case2)).isTrue();
    }

    @Test
    void convergenceDeregistersCase() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(handler.isTracking(caseId)).isFalse();
    }

    @Test
    void duplicateRecoveryEventIgnored() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        handler.onCloudEvent(recoveredEvent("node-1"));
        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
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
