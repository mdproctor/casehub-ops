package io.casehub.ops.container;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.container.podman.PodmanClient;
import io.casehub.ops.container.podman.PodmanContainer;
import io.casehub.ops.container.podman.PodmanNetwork;
import io.casehub.ops.container.podman.PodmanVolume;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PodmanActualStateAdapterTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void runningContainerIsPresentAbsentIsAbsent() {
        var podmanClient = new StubPodmanClient(
            List.of(new PodmanContainer("c1", List.of("my-app"), "running", "img:1")),
            List.of(new PodmanNetwork("my-app-net", "n1")),
            List.of(new PodmanVolume("my-app-db-data", "local")));

        var adapter = new PodmanActualStateAdapter(podmanClient);

        var graph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("my-app"), new AppContainerSpec("my-app", "img:1", Map.of(), Map.of(), Map.of()), HumanGating.NONE),
                new DesiredNode(NodeId.of("missing-app"), new AppContainerSpec("missing-app", "img:2", Map.of(), Map.of(), Map.of()), HumanGating.NONE),
                new DesiredNode(NodeId.of("my-app-net"), new NetworkSpec("my-app-net"), HumanGating.NONE),
                new DesiredNode(NodeId.of("my-app-db-data"), new VolumeSpec("my-app-db-data", "local"), HumanGating.NONE)),
            List.of());

        var actual = adapter.readActual(graph, "test-tenant");

        assertThat(actual.statusOf(NodeId.of("my-app"))).contains(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("missing-app"))).contains(NodeStatus.ABSENT);
        assertThat(actual.statusOf(NodeId.of("my-app-net"))).contains(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("my-app-db-data"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void stoppedContainerIsDrifted() {
        var podmanClient = new StubPodmanClient(
            List.of(new PodmanContainer("c1", List.of("my-db"), "exited", "postgres:16")),
            List.of(), List.of());

        var adapter = new PodmanActualStateAdapter(podmanClient);
        var graph = factory.of(
            List.of(new DesiredNode(NodeId.of("my-db"), new DatabaseContainerSpec("my-db", null, "testdb", "u", "p", "vol"), HumanGating.NONE)),
            List.of());

        var actual = adapter.readActual(graph, "test-tenant");
        assertThat(actual.statusOf(NodeId.of("my-db"))).contains(NodeStatus.DRIFTED);
    }

    @Test
    void handledTypesCoversAllContainerTypes() {
        var adapter = new PodmanActualStateAdapter(new StubPodmanClient(List.of(), List.of(), List.of()));
        assertThat(adapter.handledTypes()).containsExactlyInAnyOrder(
            ContainerNodeTypes.APP_CONTAINER, ContainerNodeTypes.DATABASE,
            ContainerNodeTypes.NETWORK, ContainerNodeTypes.VOLUME);
    }

    static class StubPodmanClient extends PodmanClient {
        private final List<PodmanContainer> containers;
        private final List<PodmanNetwork> networks;
        private final List<PodmanVolume> volumes;

        StubPodmanClient(List<PodmanContainer> containers, List<PodmanNetwork> networks, List<PodmanVolume> volumes) {
            super(null, null);
            this.containers = containers;
            this.networks = networks;
            this.volumes = volumes;
        }

        @Override public Uni<List<PodmanContainer>> listContainers() { return Uni.createFrom().item(containers); }
        @Override public Uni<List<PodmanNetwork>> listNetworks() { return Uni.createFrom().item(networks); }
        @Override public Uni<List<PodmanVolume>> listVolumes() { return Uni.createFrom().item(volumes); }
    }
}
