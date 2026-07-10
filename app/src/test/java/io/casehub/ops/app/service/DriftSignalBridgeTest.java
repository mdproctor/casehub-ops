package io.casehub.ops.app.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeDriftedData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DriftSignalBridgeTest {

    private DriftSignalBridge bridge;
    private List<SignalRecord> signals;

    @BeforeEach
    void setUp() {
        signals = new CopyOnWriteArrayList<>();
        bridge = new DriftSignalBridge(
                (caseId, path, value) -> signals.add(new SignalRecord(caseId, path, value)),
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void driftedEventSignalsCase() {
        UUID appCaseId = UUID.randomUUID();
        bridge.registerApplication("tenant-1:app-1:cluster-1", appCaseId, "app-1", "cluster-1");

        bridge.onCloudEvent(driftedEvent("tenant-1:app-1:cluster-1", "node-1"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(appCaseId);
        assertThat(signals.get(0).path()).isEqualTo("driftDetected");
    }

    @Test
    void untrackedTenancyIdIgnored() {
        bridge.onCloudEvent(driftedEvent("unknown:key", "node-1"));
        assertThat(signals).isEmpty();
    }

    @Test
    void nonDriftedEventIgnored() {
        UUID appCaseId = UUID.randomUUID();
        bridge.registerApplication("key", appCaseId, "app-1", "cluster-1");

        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("/reconciliation"))
                .withType(DesiredStateEventTypes.NODE_FAULTED)
                .withData("application/json", "{}".getBytes())
                .build();
        bridge.onCloudEvent(event);

        assertThat(signals).isEmpty();
    }

    @Test
    void consecutiveDriftCountIncrementsOnOverlap() {
        UUID appCaseId = UUID.randomUUID();
        bridge.registerApplication("key", appCaseId, "app-1", "cluster-1");

        bridge.onCloudEvent(driftedEvent("key", "node-1"));
        assertThat(signals).hasSize(1);
        assertSignalHasConsecutiveCount(signals.get(0), 1);

        bridge.onCloudEvent(driftedEvent("key", "node-1"));
        assertThat(signals).hasSize(2);
        assertSignalHasConsecutiveCount(signals.get(1), 2);
    }

    @Test
    void disjointNodeSetResetsCount() {
        UUID appCaseId = UUID.randomUUID();
        bridge.registerApplication("key", appCaseId, "app-1", "cluster-1");

        bridge.onCloudEvent(driftedEvent("key", "node-1"));
        assertSignalHasConsecutiveCount(signals.get(0), 1);

        bridge.onCloudEvent(driftedEvent("key", "node-2"));
        assertSignalHasConsecutiveCount(signals.get(1), 1);
    }

    @Test
    void resetDriftCountClearsTracking() {
        UUID appCaseId = UUID.randomUUID();
        bridge.registerApplication("key", appCaseId, "app-1", "cluster-1");

        bridge.onCloudEvent(driftedEvent("key", "node-1"));
        bridge.onCloudEvent(driftedEvent("key", "node-1"));
        assertSignalHasConsecutiveCount(signals.get(1), 2);

        bridge.resetDriftCount("app-1");

        bridge.onCloudEvent(driftedEvent("key", "node-1"));
        assertSignalHasConsecutiveCount(signals.get(2), 1);
    }

    @SuppressWarnings("unchecked")
    private void assertSignalHasConsecutiveCount(SignalRecord signal, int expected) {
        var value = (Map<String, Object>) signal.value();
        assertThat(((Number) value.get("consecutiveDriftCount")).intValue()).isEqualTo(expected);
    }

    private CloudEvent driftedEvent(String tenancyId, String nodeId) {
        try {
            var data = new NodeDriftedData(tenancyId, nodeId, "K8S_DEPLOYMENT", 1, null);
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("/reconciliation"))
                    .withType(DesiredStateEventTypes.NODE_DRIFTED)
                    .withData("application/json", mapper.writeValueAsBytes(data))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record SignalRecord(UUID caseId, String path, Object value) {}
}
