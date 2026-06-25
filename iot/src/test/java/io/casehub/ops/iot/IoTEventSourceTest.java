package io.casehub.ops.iot;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.SwitchDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTEventSourceTest {

    private static final Instant NOW = Instant.now();
    private IoTEventSource eventSource;

    @BeforeEach
    void setUp() {
        eventSource = new IoTEventSource();
    }

    @Test
    void stateChange_capabilityDrift_emitsDrifted() {
        var events = subscribe();
        var before = switchDevice("sw-1", true);
        var after = switchDevice("sw-1", false);
        eventSource.onStateChange(
            new StateChangeEvent(before, after, Set.of("isOn"), NOW, "provider-1"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().node().value()).isEqualTo("sw-1-config");
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void stateChange_newDevice_emitsPresent() {
        var events = subscribe();
        var after = switchDevice("sw-1", true);
        eventSource.onStateChange(
            new StateChangeEvent(null, after, Set.of(), NOW, "provider-1"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().node().value()).isEqualTo("sw-1");
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void stateChange_deviceOffline_emitsDrifted() {
        var events = subscribe();
        var before = switchDevice("sw-1", true);
        var after = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(false).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        eventSource.onStateChange(
            new StateChangeEvent(before, after, Set.of("available"), NOW, "p"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void providerDisconnect_emitsForKnownDevices() {
        var events = subscribe();
        var device = switchDevice("sw-1", true);
        eventSource.onStateChange(
            new StateChangeEvent(null, device, Set.of(), NOW, "provider-1"));
        events.clear();

        eventSource.onProviderStatus(
            new ProviderStatusEvent("provider-1", ProviderStatus.CONNECTED, ProviderStatus.DISCONNECTED));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.UNKNOWN);
    }

    @Test
    void providerDisconnect_noKnownDevices_noEmit() {
        var events = subscribe();
        eventSource.onProviderStatus(
            new ProviderStatusEvent("unknown-provider", ProviderStatus.CONNECTED, ProviderStatus.DISCONNECTED));
        assertThat(events).isEmpty();
    }

    private SwitchDevice switchDevice(String id, boolean on) {
        return SwitchDevice.builder()
            .deviceId(id).deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("provider-1")
            .on(on).build();
    }

    private List<StateEvent> subscribe() {
        var collected = new ArrayList<StateEvent>();
        eventSource.stream().subscribe().with(collected::add);
        return collected;
    }
}
