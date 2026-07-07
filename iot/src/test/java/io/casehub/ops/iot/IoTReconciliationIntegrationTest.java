package io.casehub.ops.iot;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.LockDevice;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTReconciliationIntegrationTest {

    private static final Instant NOW = Instant.now();
    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test
    void fullReconciliationCycle() {
        var dispatched = new ArrayList<DeviceCommand>();

        var light = new LightDevice.Builder()
            .deviceId("light-1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("test")
            .on(true).brightness(50).build();
        var lock = new LockDevice.Builder()
            .deviceId("lock-1").deviceClass(DeviceClass.LOCK).label("Lock")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("test")
            .locked(true).build();

        var devices = new HashMap<String, DeviceEntity>();
        devices.put("light-1", light);
        devices.put("lock-1", lock);

        DeviceRegistry registry = new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) { return Optional.ofNullable(devices.get(id)); }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.copyOf(devices.values()); }
            public Uni<Void> refresh() { return Uni.createFrom().voidItem(); }
        };

        DeviceProvider provider = new DeviceProvider() {
            public String providerId() { return "test"; }
            public Uni<List<DeviceEntity>> discover() { return Uni.createFrom().item(List.of()); }
            public Uni<CommandResult> dispatch(DeviceCommand cmd) {
                dispatched.add(cmd);
                return Uni.createFrom().item(CommandResult.SENT);
            }
            public ProviderStatus status() { return ProviderStatus.CONNECTED; }
        };

        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("thermo-1", DeviceClass.THERMOSTAT, "Thermostat", true,
                Map.of("targetTemperature", Map.of("value", 22, "unit", "CELSIUS")), List.of()),
            new IoTDeviceGoal("light-1", DeviceClass.LIGHT, "Light", true,
                Map.of("isOn", true, "brightness", 80), List.of()),
            new IoTDeviceGoal("lock-1", DeviceClass.LOCK, "Lock", true,
                Map.of("isLocked", true), List.of())));

        var compiler = new IoTGoalCompiler();
        var adapter = new IoTActualStateAdapter(registry);
        var approvalEvaluator = new IoTApprovalEvaluator();
        var planStore = new io.casehub.ops.api.approval.InMemoryPlanStore();
        var provisioner = new IoTNodeProvisioner(registry, List.of(provider), approvalEvaluator, planStore);
        var planner = new TransitionPlanner();

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, FACTORY)).graph();
        var actual = adapter.readActual(graph, "tenant-1");

        assertThat(actual.statusOf(NodeId.of("thermo-1"))).contains(NodeStatus.ABSENT);
        assertThat(actual.statusOf(NodeId.of("light-1"))).contains(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("light-1-config"))).contains(NodeStatus.DRIFTED);
        assertThat(actual.statusOf(NodeId.of("lock-1"))).contains(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("lock-1-config"))).contains(NodeStatus.PRESENT);

        var plan = planner.plan(graph, actual);

        for (var step : plan.additions()) {
            if (step.action() == StepAction.PROVISION) {
                var result = provisioner.provision(step.node(),
                    new ProvisionContext("default", graph));
                if (step.node().id().value().equals("light-1-config")) {
                    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
                }
            }
        }

        assertThat(dispatched).anySatisfy(cmd -> {
            assertThat(cmd.targetDeviceId()).isEqualTo("light-1");
            assertThat(cmd.action()).isEqualTo("set_brightness");
        });
    }
}
