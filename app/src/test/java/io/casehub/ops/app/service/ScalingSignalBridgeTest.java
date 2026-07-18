package io.casehub.ops.app.service;

import io.casehub.ops.app.case_.ScalingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class ScalingSignalBridgeTest {

    private ScalingSignalBridge bridge;
    private List<SignalRecord> signals;

    @BeforeEach
    void setUp() {
        signals = new CopyOnWriteArrayList<>();
        bridge = new ScalingSignalBridge(
                (caseId, path, value) -> signals.add(new SignalRecord(caseId, path, value)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventSignalsCorrectCaseWithFullSpec() {
        UUID caseId = UUID.randomUUID();
        var event = new ScalingRequestedEvent(caseId, "app-1", "tenant-1",
                "web", 6, 3, "high-load", new ScalingPolicy(2, 10, Duration.ofMinutes(5)));

        bridge.onScalingRequested(event);

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(caseId);
        assertThat(signals.get(0).path()).isEqualTo("scalingRequired");

        Map<String, Object> spec = (Map<String, Object>) signals.get(0).value();
        assertThat(spec.get("serviceId")).isEqualTo("web");
        assertThat(spec.get("targetReplicas")).isEqualTo(6);
        assertThat(spec.get("currentReplicas")).isEqualTo(3);
        assertThat(spec.get("applicationId")).isEqualTo("app-1");
        assertThat(spec.get("tenancyId")).isEqualTo("tenant-1");
        assertThat(spec.get("reason")).isEqualTo("high-load");
        assertThat(spec.get("minReplicas")).isEqualTo(2);
        assertThat(spec.get("maxReplicas")).isEqualTo(10);
        assertThat(spec.get("cooldownSeconds")).isEqualTo(300L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unboundedPolicyOmitsCooldown() {
        UUID caseId = UUID.randomUUID();
        var event = new ScalingRequestedEvent(caseId, "app-1", "tenant-1",
                "web", 5, 2, "manual", ScalingPolicy.UNBOUNDED);

        bridge.onScalingRequested(event);

        Map<String, Object> spec = (Map<String, Object>) signals.get(0).value();
        assertThat(spec).doesNotContainKey("cooldownSeconds");
    }

    record SignalRecord(UUID caseId, String path, Object value) {}
}
