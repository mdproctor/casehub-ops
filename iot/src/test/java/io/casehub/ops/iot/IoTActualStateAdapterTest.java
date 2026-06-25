package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTActualStateAdapterTest {

    private static final Instant NOW = Instant.now();
    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test
    void devicePresent_configMatches_present() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singleConfigGraph("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1-config"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void devicePresent_configDrifted_drifted() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(false).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singleConfigGraph("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1-config"))).contains(NodeStatus.DRIFTED);
    }

    @Test
    void deviceAbsent_absent() {
        var registry = stubRegistry();
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singlePhysicalGraph("sw-1", DeviceClass.SWITCH);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1"))).contains(NodeStatus.ABSENT);
    }

    @Test
    void physicalPresent_classMatches_present() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singlePhysicalGraph("sw-1", DeviceClass.SWITCH);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void physicalPresent_wrongClass_drifted() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singlePhysicalGraph("sw-1", DeviceClass.THERMOSTAT);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1"))).contains(NodeStatus.DRIFTED);
    }

    private DesiredStateGraph singleConfigGraph(String deviceId, DeviceClass dc,
                                                 Map<String, Object> caps) {
        return FACTORY.of(
            List.of(new DesiredNode(NodeId.of(deviceId + "-config"),
                NodeType.of("device-config"),
                new DeviceConfigSpec(deviceId, dc, caps), false)),
            List.of());
    }

    private DesiredStateGraph singlePhysicalGraph(String deviceId, DeviceClass dc) {
        return FACTORY.of(
            List.of(new DesiredNode(NodeId.of(deviceId),
                NodeType.of("physical-device"),
                new PhysicalDeviceSpec(deviceId, dc, "Label"), true)),
            List.of());
    }

    private DeviceRegistry stubRegistry(DeviceEntity... devices) {
        return new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) {
                for (var d : devices) if (d.deviceId().equals(id)) return Optional.of(d);
                return Optional.empty();
            }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.of(devices); }
            public io.smallrye.mutiny.Uni<Void> refresh() { return io.smallrye.mutiny.Uni.createFrom().voidItem(); }
        };
    }
}
