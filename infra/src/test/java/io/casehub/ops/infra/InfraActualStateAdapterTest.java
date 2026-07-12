package io.casehub.ops.infra;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.spi.BackendDeprovisionResult;
import io.casehub.ops.api.infra.spi.BackendProvisionResult;
import io.casehub.ops.api.infra.spi.InfraBackend;
import io.casehub.ops.api.infra.state.DriftReport;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.types.Labels;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InfraActualStateAdapterTest {

    private static final Instant                  NOW           = Instant.now();
    private static final DesiredStateGraphFactory GRAPH_FACTORY = new DefaultDesiredStateGraphFactory();

    // --- ConfigurableBackend ---

    private DesiredNode infraNode(String id, String backendId) {
        var spec = new K8sNamespaceSpec(id, Labels.empty());
        return new DesiredNode(NodeId.of(id), NodeType.of("k8s_namespace"),
                               new InfraDesiredNodeSpec(spec, backendId), false);
    }

    // --- helpers ---

    private DesiredStateGraph graphOf(DesiredNode... nodes) {
        return GRAPH_FACTORY.of(List.of(nodes), List.of());
    }

    @Test
    void readsStateForAllNodesInGraph() {
        var terraform  = new ConfigurableBackend("terraform");
        var standalone = new ConfigurableBackend("standalone");
        var adapter    = new InfraActualStateAdapter(List.of(terraform, standalone));

        var graph = graphOf(
                infraNode("ns-1", "terraform"),
                infraNode("ns-2", "standalone"));

        ActualState actual = adapter.readActual(graph, "tenant-1");

        assertThat(actual.statuses()).hasSize(2);
        assertThat(actual.statusOf(NodeId.of("ns-1"))).hasValue(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("ns-2"))).hasValue(NodeStatus.PRESENT);
    }

    // --- tests ---

    @Test
    void mapsHealthyToPresent() {
        var backend = new ConfigurableBackend("terraform");
        backend.willReturnStatus(ResourceStatus.HEALTHY);
        var adapter = new InfraActualStateAdapter(List.of(backend));

        var graph = graphOf(infraNode("ns-1", "terraform"));

        ActualState actual = adapter.readActual(graph, "tenant-1");

        assertThat(actual.statusOf(NodeId.of("ns-1"))).hasValue(NodeStatus.PRESENT);
    }

    @Test
    void mapsUnavailableToAbsent() {
        var backend = new ConfigurableBackend("terraform");
        backend.willReturnStatus(ResourceStatus.UNAVAILABLE);
        var adapter = new InfraActualStateAdapter(List.of(backend));

        var graph = graphOf(infraNode("ns-1", "terraform"));

        ActualState actual = adapter.readActual(graph, "tenant-1");

        assertThat(actual.statusOf(NodeId.of("ns-1"))).hasValue(NodeStatus.ABSENT);
    }

    @Test
    void mapsDriftedToDrifted() {
        var backend = new ConfigurableBackend("terraform");
        backend.willReturnStatus(ResourceStatus.DRIFTED);
        var adapter = new InfraActualStateAdapter(List.of(backend));

        var graph = graphOf(infraNode("ns-1", "terraform"));

        ActualState actual = adapter.readActual(graph, "tenant-1");

        assertThat(actual.statusOf(NodeId.of("ns-1"))).hasValue(NodeStatus.DRIFTED);
    }

    @Test
    void handlesReadStateFailure() {
        var backend = new ConfigurableBackend("terraform");
        backend.willFailFor(NodeId.of("ns-broken"));
        var adapter = new InfraActualStateAdapter(List.of(backend));

        var graph = graphOf(infraNode("ns-broken", "terraform"));

        ActualState actual = adapter.readActual(graph, "tenant-1");

        assertThat(actual.statusOf(NodeId.of("ns-broken"))).hasValue(NodeStatus.UNKNOWN);
    }

    @Test
    void passesInfraNodeSpecThroughToBackend() {
        var backend = new ConfigurableBackend("standalone");
        var adapter = new InfraActualStateAdapter(List.of(backend));

        var spec = new K8sNamespaceSpec("prod-ns", Labels.empty());
        var node = new DesiredNode(NodeId.of("ns-1"), NodeType.of("k8s_namespace"),
                                   new InfraDesiredNodeSpec(spec, "standalone"), false);
        var graph = graphOf(node);

        adapter.readActual(graph, "tenant-1");

        assertThat(backend.receivedSpecs()).hasSize(1);
        assertThat(backend.receivedSpecs().get(0)).isEqualTo(spec);
    }

    /**
     * Test double that returns configurable ResourceState per node, or fails on demand.
     */
    static class ConfigurableBackend implements InfraBackend {

        private final String              id;
        private final List<InfraNodeSpec> receivedSpecs = new java.util.ArrayList<>();

        private ResourceStatus defaultStatus = ResourceStatus.HEALTHY;
        private NodeId         failForNode   = null;

        ConfigurableBackend(String id) {
            this.id = id;
        }

        void willReturnStatus(ResourceStatus status) {
            this.defaultStatus = status;
        }

        void willFailFor(NodeId nodeId) {
            this.failForNode = nodeId;
        }

        List<InfraNodeSpec> receivedSpecs() {
            return receivedSpecs;
        }


        @Override
        public String backendId() {
            return id;
        }

        @Override
        public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not needed"));
        }

        @Override
        public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not needed"));
        }

        @Override
        public Uni<ResourceState> readState(NodeId nodeId, InfraNodeSpec spec) {
            receivedSpecs.add(spec);
            if (nodeId.equals(failForNode)) {
                return Uni.createFrom().failure(new RuntimeException("backend unreachable"));
            }
            return Uni.createFrom().item(new ResourceState(
                    nodeId, "generic", defaultStatus, NOW, null, ResourceOutputs.empty()));
        }

        @Override
        public Uni<DriftReport> detectDrift(NodeId nodeId, InfraNodeSpec spec) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not needed"));
        }

        @Override
        public Uni<Optional<io.casehub.ops.api.infra.plan.ProvisionPlan>> plan(
                InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().failure(new UnsupportedOperationException("not needed"));
        }
    }

}
