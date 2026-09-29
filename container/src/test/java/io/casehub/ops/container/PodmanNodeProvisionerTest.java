package io.casehub.ops.container;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.container.podman.PodmanClient;
import io.casehub.ops.container.podman.PodmanContainer;
import io.casehub.ops.container.podman.PodmanNetwork;
import io.casehub.ops.container.podman.PodmanVolume;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PodmanNodeProvisionerTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void provisionAppContainerReturnsSuccess() {
        var recorder = new RecordingPodmanClient();
        var provisioner = new PodmanNodeProvisioner(recorder);

        var spec = new AppContainerSpec("my-app", "img:1", Map.of(8080, 8080), Map.of("K", "V"), Map.of());
        var node = new DesiredNode(NodeId.of("my-app"), spec, HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.provision(node, new ProvisionContext("tenant", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(recorder.pulledImages).contains("img:1");
        assertThat(recorder.createdContainers).hasSize(1);
        assertThat(recorder.startedContainers).hasSize(1);
    }

    @Test
    void provisionDatabaseContainerReturnsSuccess() {
        var recorder = new RecordingPodmanClient();
        var provisioner = new PodmanNodeProvisioner(recorder);

        var spec = new DatabaseContainerSpec("my-db", null, "testdb", "admin", "secret", "db-data");
        var node = new DesiredNode(NodeId.of("my-db"), spec, HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.provision(node, new ProvisionContext("tenant", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(recorder.pulledImages).contains("postgres:16");
    }

    @Test
    void provisionNetworkReturnsSuccess() {
        var recorder = new RecordingPodmanClient();
        var provisioner = new PodmanNodeProvisioner(recorder);

        var spec = new NetworkSpec("ops-net");
        var node = new DesiredNode(NodeId.of("ops-net"), spec, HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.provision(node, new ProvisionContext("tenant", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(recorder.createdNetworks).contains("ops-net");
    }

    @Test
    void provisionVolumeReturnsSuccess() {
        var recorder = new RecordingPodmanClient();
        var provisioner = new PodmanNodeProvisioner(recorder);

        var spec = new VolumeSpec("data-vol", "local");
        var node = new DesiredNode(NodeId.of("data-vol"), spec, HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.provision(node, new ProvisionContext("tenant", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(recorder.createdVolumes).contains("data-vol");
    }

    @Test
    void deprovisionContainerStopsAndRemoves() {
        var recorder = new RecordingPodmanClient();
        recorder.existingContainers.add(new PodmanContainer("c1", List.of("my-app"), "running", "img:1"));
        var provisioner = new PodmanNodeProvisioner(recorder);

        var spec = new AppContainerSpec("my-app", "img:1", Map.of(), Map.of(), Map.of());
        var node = new DesiredNode(NodeId.of("my-app"), spec, HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());

        var result = provisioner.deprovision(node, new DeprovisionContext("tenant", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(recorder.stoppedContainers).contains("c1");
        assertThat(recorder.removedContainers).contains("c1");
    }

    @Test
    void handledTypesCoversAllContainerTypes() {
        var provisioner = new PodmanNodeProvisioner(new RecordingPodmanClient());
        assertThat(provisioner.handledTypes()).containsExactlyInAnyOrder(
            ContainerNodeTypes.APP_CONTAINER, ContainerNodeTypes.DATABASE,
            ContainerNodeTypes.NETWORK, ContainerNodeTypes.VOLUME);
    }

    static class RecordingPodmanClient extends PodmanClient {
        final List<String> pulledImages = new ArrayList<>();
        final List<PodmanClient.CreateContainerRequest> createdContainers = new ArrayList<>();
        final List<String> startedContainers = new ArrayList<>();
        final List<String> stoppedContainers = new ArrayList<>();
        final List<String> removedContainers = new ArrayList<>();
        final List<String> createdNetworks = new ArrayList<>();
        final List<String> createdVolumes = new ArrayList<>();
        final List<PodmanContainer> existingContainers = new ArrayList<>();

        RecordingPodmanClient() { super(null, null); }

        @Override public Uni<Void> pullImage(String image) {
            pulledImages.add(image); return Uni.createFrom().voidItem();
        }
        @Override public Uni<String> createContainer(CreateContainerRequest req) {
            createdContainers.add(req); return Uni.createFrom().item("new-id");
        }
        @Override public Uni<Void> startContainer(String id) {
            startedContainers.add(id); return Uni.createFrom().voidItem();
        }
        @Override public Uni<Void> stopContainer(String id) {
            stoppedContainers.add(id); return Uni.createFrom().voidItem();
        }
        @Override public Uni<Void> removeContainer(String id) {
            removedContainers.add(id); return Uni.createFrom().voidItem();
        }
        @Override public Uni<PodmanNetwork> createNetwork(String name) {
            createdNetworks.add(name); return Uni.createFrom().item(new PodmanNetwork(name, "new-net-id"));
        }
        @Override public Uni<PodmanVolume> createVolume(String name, String driver) {
            createdVolumes.add(name); return Uni.createFrom().item(new PodmanVolume(name, driver));
        }
        @Override public Uni<List<PodmanContainer>> listContainers() {
            return Uni.createFrom().item(List.copyOf(existingContainers));
        }
    }
}
