package io.casehub.ops.container;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.ops.container.podman.PodmanClient;
import io.casehub.ops.container.podman.PodmanContainer;
import io.casehub.ops.container.podman.PodmanNetwork;
import io.casehub.ops.container.podman.PodmanVolume;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerReconciliationIntegrationTest {

    private static final String TENANCY_ID = "tenant-1";

    private SimulatingPodmanClient podmanClient;
    private ContainerGoalCompiler compiler;
    private PodmanActualStateAdapter adapter;
    private PodmanNodeProvisioner provisioner;
    private TransitionPlanner planner;
    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        podmanClient = new SimulatingPodmanClient();
        compiler = new ContainerGoalCompiler();
        adapter = new PodmanActualStateAdapter(podmanClient);
        provisioner = new PodmanNodeProvisioner(podmanClient);
        planner = new TransitionPlanner();
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void greenField_compileAndProvisionAll_loopClosure() {
        var descriptor = fsiTradingDescriptor();
        var desired = compileSingleGraph(descriptor);
        assertThat(desired.nodes()).hasSize(4);

        var actual = adapter.readActual(desired, TENANCY_ID);
        for (var status : actual.statuses().values()) {
            assertThat(status).isEqualTo(NodeStatus.ABSENT);
        }

        var plan = planner.plan(desired, actual);
        assertThat(plan.flatAdditions()).isNotEmpty();

        for (var step : plan.flatAdditions()) {
            if (step.action() == StepAction.PROVISION) {
                var result = provisioner.provision(step.node(), new ProvisionContext(TENANCY_ID, desired));
                assertThat(result).as("provisioning %s", step.node().id()).isInstanceOf(ProvisionResult.Success.class);
            }
        }

        var actualAfter = adapter.readActual(desired, TENANCY_ID);
        for (var entry : actualAfter.statuses().entrySet()) {
            assertThat(entry.getValue()).as("node %s", entry.getKey()).isEqualTo(NodeStatus.PRESENT);
        }

        var secondPlan = planner.plan(desired, actualAfter);
        assertThat(secondPlan.isEmpty()).isTrue();
    }

    @Test
    void selfHealing_containerDestroyed_reconciliationReprovisions() {
        var descriptor = fsiTradingDescriptor();
        var desired = compileSingleGraph(descriptor);
        provisionAll(desired);

        var beforeDestroy = adapter.readActual(desired, TENANCY_ID);
        assertThat(beforeDestroy.statusOf(NodeId.of("fsitrading"))).contains(NodeStatus.PRESENT);

        podmanClient.destroyContainer("fsitrading");

        var afterDestroy = adapter.readActual(desired, TENANCY_ID);
        assertThat(afterDestroy.statusOf(NodeId.of("fsitrading"))).contains(NodeStatus.ABSENT);

        var healingPlan = planner.plan(desired, afterDestroy);
        assertThat(healingPlan.flatAdditions()).anySatisfy(step -> {
            assertThat(step.node().id()).isEqualTo(NodeId.of("fsitrading"));
            assertThat(step.action()).isEqualTo(StepAction.PROVISION);
        });

        for (var step : healingPlan.flatAdditions()) {
            if (step.action() == StepAction.PROVISION) {
                var result = provisioner.provision(step.node(), new ProvisionContext(TENANCY_ID, desired));
                assertThat(result).isInstanceOf(ProvisionResult.Success.class);
            }
        }

        var afterHealing = adapter.readActual(desired, TENANCY_ID);
        assertThat(afterHealing.statusOf(NodeId.of("fsitrading"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void driftDetection_stoppedContainer_reconciliationRestarts() {
        var descriptor = fsiTradingDescriptor();
        var desired = compileSingleGraph(descriptor);
        provisionAll(desired);

        podmanClient.stopContainerByName("fsitrading-db");

        var afterStop = adapter.readActual(desired, TENANCY_ID);
        assertThat(afterStop.statusOf(NodeId.of("fsitrading-db"))).contains(NodeStatus.DRIFTED);

        var driftPlan = planner.plan(desired, afterStop);
        assertThat(driftPlan.flatAdditions()).anySatisfy(step -> {
            assertThat(step.node().id()).isEqualTo(NodeId.of("fsitrading-db"));
            assertThat(step.action()).isEqualTo(StepAction.PROVISION);
        });

        for (var step : driftPlan.flatAdditions()) {
            if (step.action() == StepAction.PROVISION) {
                provisioner.provision(step.node(), new ProvisionContext(TENANCY_ID, desired));
            }
        }

        var afterRestart = adapter.readActual(desired, TENANCY_ID);
        assertThat(afterRestart.statusOf(NodeId.of("fsitrading-db"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void deprovision_removesContainersFromActualState() {
        var descriptor = fsiTradingDescriptor();
        var desired = compileSingleGraph(descriptor);
        provisionAll(desired);

        var emptyGraph = graphFactory.of(List.of(), List.of());
        var actual = adapter.readActual(desired, TENANCY_ID);
        var removalPlan = planner.plan(emptyGraph, actual);

        assertThat(removalPlan.flatRemovals()).isNotEmpty();

        for (var step : removalPlan.flatRemovals()) {
            if (step.action() == StepAction.DEPROVISION) {
                var originalNode = desired.nodes().get(step.node().id());
                var result = provisioner.deprovision(originalNode, new DeprovisionContext(TENANCY_ID, emptyGraph));
                assertThat(result).as("deprovisioning %s", step.node().id()).isInstanceOf(DeprovisionResult.Success.class);
            }
        }

        assertThat(podmanClient.containers).isEmpty();
    }

    @Test
    void dependencyOrdering_networkAndVolumeProvisionedBeforeContainers() {
        var descriptor = fsiTradingDescriptor();
        var desired = compileSingleGraph(descriptor);

        var actual = adapter.readActual(desired, TENANCY_ID);
        var plan = planner.plan(desired, actual);

        var provisionOrder = plan.flatAdditions().stream()
            .filter(s -> s.action() == StepAction.PROVISION)
            .map(s -> s.node().id())
            .toList();

        var netIdx = provisionOrder.indexOf(NodeId.of("fsitrading-net"));
        var volIdx = provisionOrder.indexOf(NodeId.of("fsitrading-db-data"));
        var dbIdx = provisionOrder.indexOf(NodeId.of("fsitrading-db"));
        var appIdx = provisionOrder.indexOf(NodeId.of("fsitrading"));

        assertThat(netIdx).as("network before db").isLessThan(dbIdx);
        assertThat(volIdx).as("volume before db").isLessThan(dbIdx);
        assertThat(dbIdx).as("db before app").isLessThan(appIdx);
    }

    private DeploymentDescriptor fsiTradingDescriptor() {
        return new DeploymentDescriptor(
            "fsitrading", "casehub/fsitrading:latest",
            Map.of(8080, 18080), Map.of("QUARKUS_PROFILE", "prod"),
            "fsitrading_db");
    }

    private DesiredStateGraph compileSingleGraph(DeploymentDescriptor descriptor) {
        var result = compiler.compile(descriptor, graphFactory);
        return ((CompilationResult.SingleGraph) result).graph();
    }

    private void provisionAll(DesiredStateGraph desired) {
        var actual = adapter.readActual(desired, TENANCY_ID);
        var plan = planner.plan(desired, actual);
        for (var step : plan.flatAdditions()) {
            if (step.action() == StepAction.PROVISION) {
                provisioner.provision(step.node(), new ProvisionContext(TENANCY_ID, desired));
            }
        }
    }

    public static class SimulatingPodmanClient extends PodmanClient {

        final ConcurrentHashMap<String, SimContainer> containers = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, PodmanNetwork> networks = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, PodmanVolume> volumes = new ConcurrentHashMap<>();

        SimulatingPodmanClient() {
            super(null, null);
        }

        @Override
        public Uni<Void> pullImage(String image) {
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<String> createContainer(CreateContainerRequest req) {
            var id = "sim-" + req.name();
            containers.put(req.name(), new SimContainer(id, req.name(), "created", req.image()));
            return Uni.createFrom().item(id);
        }

        @Override
        public Uni<Void> startContainer(String id) {
            containers.values().stream()
                .filter(c -> c.id.equals(id))
                .findFirst()
                .ifPresent(c -> containers.put(c.name, c.withState("running")));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> stopContainer(String id) {
            containers.values().stream()
                .filter(c -> c.id.equals(id))
                .findFirst()
                .ifPresent(c -> containers.put(c.name, c.withState("exited")));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> removeContainer(String id) {
            containers.values().removeIf(c -> c.id.equals(id));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<List<PodmanContainer>> listContainers() {
            var result = containers.values().stream()
                .map(c -> new PodmanContainer(c.id, List.of(c.name), c.state, c.image))
                .toList();
            return Uni.createFrom().item(result);
        }

        @Override
        public Uni<PodmanNetwork> createNetwork(String name) {
            var net = new PodmanNetwork(name, "sim-net-" + name);
            networks.put(name, net);
            return Uni.createFrom().item(net);
        }

        @Override
        public Uni<List<PodmanNetwork>> listNetworks() {
            return Uni.createFrom().item(List.copyOf(networks.values()));
        }

        @Override
        public Uni<PodmanVolume> createVolume(String name, String driver) {
            var vol = new PodmanVolume(name, driver);
            volumes.put(name, vol);
            return Uni.createFrom().item(vol);
        }

        @Override
        public Uni<List<PodmanVolume>> listVolumes() {
            return Uni.createFrom().item(List.copyOf(volumes.values()));
        }

        void destroyContainer(String name) {
            containers.remove(name);
        }

        void stopContainerByName(String name) {
            var c = containers.get(name);
            if (c != null) {
                containers.put(name, c.withState("exited"));
            }
        }

        private volatile MultiEmitter<? super JsonObject> eventEmitter;
        private final Multi<JsonObject> eventStream = Multi.createFrom()
            .<JsonObject>emitter(e -> this.eventEmitter = e, BackPressureStrategy.BUFFER)
            .broadcast().toAllSubscribers();

        @Override
        public Multi<JsonObject> events(String type) {
            return eventStream;
        }

        void emitEvent(JsonObject event) {
            var e = this.eventEmitter;
            if (e != null) e.emit(event);
        }

        record SimContainer(String id, String name, String state, String image) {
            SimContainer withState(String newState) {
                return new SimContainer(id, name, newState, image);
            }
        }
    }
}
