package io.casehub.ops.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.spi.BackendDeprovisionResult;
import io.casehub.ops.api.infra.spi.BackendProvisionResult;
import io.casehub.ops.api.infra.spi.InfraBackend;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.types.Labels;
import io.smallrye.mutiny.Uni;

class InfraNodeProvisionerTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final Instant NOW = Instant.now();

    // --- TrackingBackend ---

    /**
     * Test double that records calls and returns configurable results.
     */
    static class TrackingBackend implements InfraBackend {

        private final String id;
        private final List<InfraNodeSpec> provisionedSpecs = new ArrayList<>();
        private final List<InfraNodeSpec> deprovisionedSpecs = new ArrayList<>();
        private BackendProvisionResult provisionResult;
        private BackendDeprovisionResult deprovisionResult;

        TrackingBackend(String id) {
            this.id = id;
            // default: succeed
            this.provisionResult = new BackendProvisionResult.Provisioned(
                    new ResourceState(NODE_1, "k8s_namespace", ResourceStatus.HEALTHY,
                            NOW, null, ResourceOutputs.empty()));
            this.deprovisionResult = new BackendDeprovisionResult.Deprovisioned(NODE_1);
        }

        void willReturn(BackendProvisionResult result) {
            this.provisionResult = result;
        }

        void willReturn(BackendDeprovisionResult result) {
            this.deprovisionResult = result;
        }

        List<InfraNodeSpec> provisionedSpecs() {
            return provisionedSpecs;
        }

        List<InfraNodeSpec> deprovisionedSpecs() {
            return deprovisionedSpecs;
        }

        @Override
        public String backendId() {
            return id;
        }

        @Override
        public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context) {
            provisionedSpecs.add(spec);
            return Uni.createFrom().item(provisionResult);
        }

        @Override
        public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context) {
            deprovisionedSpecs.add(spec);
            return Uni.createFrom().item(deprovisionResult);
        }

        @Override
        public Uni<ResourceState> readState(NodeId nodeId) {
            return Uni.createFrom().item(new ResourceState(
                    nodeId, "generic", ResourceStatus.HEALTHY, NOW, null, ResourceOutputs.empty()));
        }

        @Override
        public Uni<io.casehub.ops.api.infra.state.DriftReport> detectDrift(NodeId nodeId) {
            return Uni.createFrom().item(new io.casehub.ops.api.infra.state.DriftReport(
                    nodeId, false, List.of(), NOW, id));
        }

        @Override
        public Uni<Optional<io.casehub.ops.api.infra.plan.ProvisionPlan>> plan(
                InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(Optional.empty());
        }
    }

    // --- helpers ---

    private static final DefaultDesiredStateGraphFactory GRAPH_FACTORY = new DefaultDesiredStateGraphFactory();

    private DesiredNode infraNode(NodeId nodeId, InfraNodeSpec resourceSpec, String backendId) {
        return new DesiredNode(nodeId, NodeType.of(resourceSpec.resourceType()),
                new InfraDesiredNodeSpec(resourceSpec, backendId), false);
    }

    private DesiredStateGraph graphOf(DesiredNode... nodes) {
        return GRAPH_FACTORY.of(List.of(nodes), List.of());
    }

    private ProvisionContext provisionContext(DesiredStateGraph graph) {
        return new ProvisionContext("tenant-1", graph);
    }

    private DeprovisionContext deprovisionContext(DesiredStateGraph graph) {
        return new DeprovisionContext("tenant-1", graph);
    }

    // --- tests ---

    @Test
    void dispatchesToCorrectBackendByBackendId() {
        var terraform = new TrackingBackend("terraform");
        var standalone = new TrackingBackend("standalone");
        var provisioner = new InfraNodeProvisioner(List.of(terraform, standalone));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");
        var graph = graphOf(node);

        ProvisionResult result = provisioner.provision(node, provisionContext(graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(terraform.provisionedSpecs()).hasSize(1);
        assertThat(terraform.provisionedSpecs().get(0)).isEqualTo(spec);
        assertThat(standalone.provisionedSpecs()).isEmpty();
    }

    @Test
    void failsWhenNoBackendMatchesBackendId() {
        var terraform = new TrackingBackend("terraform");
        var provisioner = new InfraNodeProvisioner(List.of(terraform));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "nonexistent");
        var graph = graphOf(node);

        ProvisionResult result = provisioner.provision(node, provisionContext(graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        var failed = (ProvisionResult.Failed) result;
        assertThat(failed.reason()).contains("nonexistent");
    }

    @Test
    void failsWhenSpecIsNotInfraDesiredNodeSpec() {
        var provisioner = new InfraNodeProvisioner(List.of(new TrackingBackend("terraform")));

        // Use a raw NodeSpec that is NOT InfraDesiredNodeSpec
        NodeSpec rawSpec = new NodeSpec() {};
        var node = new DesiredNode(NODE_1, NodeType.of("raw"), rawSpec, false);
        var graph = graphOf(node);

        ProvisionResult result = provisioner.provision(node, provisionContext(graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        var failed = (ProvisionResult.Failed) result;
        assertThat(failed.reason()).contains("InfraDesiredNodeSpec");
    }

    @Test
    void mapsBackendFailedToProvisionResultFailed() {
        var backend = new TrackingBackend("terraform");
        backend.willReturn(new BackendProvisionResult.Failed("cloud quota exceeded", true));
        var provisioner = new InfraNodeProvisioner(List.of(backend));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");
        var graph = graphOf(node);

        ProvisionResult result = provisioner.provision(node, provisionContext(graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        var failed = (ProvisionResult.Failed) result;
        assertThat(failed.reason()).isEqualTo("cloud quota exceeded");
    }

    @Test
    void deprovisionDispatchesCorrectly() {
        var backend = new TrackingBackend("standalone");
        var provisioner = new InfraNodeProvisioner(List.of(backend));

        var spec = new K8sNamespaceSpec("staging", Labels.empty());
        var node = infraNode(NODE_1, spec, "standalone");
        var graph = graphOf(node);

        DeprovisionResult result = provisioner.deprovision(node, deprovisionContext(graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(backend.deprovisionedSpecs()).hasSize(1);
        assertThat(backend.deprovisionedSpecs().get(0)).isEqualTo(spec);
    }
}
