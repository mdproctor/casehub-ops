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
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.ApprovalThresholds;
import io.casehub.ops.api.approval.InMemoryPlanStore;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.plan.ChangeAction;
import io.casehub.ops.api.infra.plan.PlannedChange;
import io.casehub.ops.api.infra.plan.ProvisionPlan;
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
        private final List<InfraProvisionContext> provisionContexts = new ArrayList<>();
        private final List<InfraProvisionContext> deprovisionContexts = new ArrayList<>();
        private BackendProvisionResult provisionResult;
        private BackendDeprovisionResult deprovisionResult;
        private Optional<ProvisionPlan> planResult = Optional.empty();

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

        void willReturnPlan(ProvisionPlan plan) {
            this.planResult = Optional.of(plan);
        }

        List<InfraNodeSpec> provisionedSpecs() {
            return provisionedSpecs;
        }

        List<InfraNodeSpec> deprovisionedSpecs() {
            return deprovisionedSpecs;
        }

        List<InfraProvisionContext> provisionContexts() {
            return provisionContexts;
        }

        List<InfraProvisionContext> deprovisionContexts() {
            return deprovisionContexts;
        }

        @Override
        public String backendId() {
            return id;
        }

        @Override
        public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context) {
            provisionedSpecs.add(spec);
            provisionContexts.add(context);
            return Uni.createFrom().item(provisionResult);
        }

        @Override
        public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context) {
            deprovisionedSpecs.add(spec);
            deprovisionContexts.add(context);
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
        public Uni<Optional<ProvisionPlan>> plan(
                InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(planResult);
        }
    }

    // --- AlwaysAutoApproveEvaluator ---

    static class AlwaysAutoApproveEvaluator implements ApprovalEvaluator {
        @Override
        public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
            return new ApprovalDecision.AutoApproved();
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

    private InfraNodeProvisioner provisionerWithAutoApprove(List<InfraBackend> backends) {
        return new InfraNodeProvisioner(backends, new AlwaysAutoApproveEvaluator(), new InMemoryPlanStore());
    }

    // --- existing dispatch tests (updated for new constructor) ---

    @Test
    void dispatchesToCorrectBackendByBackendId() {
        var terraform = new TrackingBackend("terraform");
        var standalone = new TrackingBackend("standalone");
        var provisioner = provisionerWithAutoApprove(List.of(terraform, standalone));

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
        var provisioner = provisionerWithAutoApprove(List.of(terraform));

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
        var provisioner = provisionerWithAutoApprove(List.of(new TrackingBackend("terraform")));

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
        var provisioner = provisionerWithAutoApprove(List.of(backend));

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
        var provisioner = provisionerWithAutoApprove(List.of(backend));

        var spec = new K8sNamespaceSpec("staging", Labels.empty());
        var node = infraNode(NODE_1, spec, "standalone");
        var graph = graphOf(node);

        DeprovisionResult result = provisioner.deprovision(node, deprovisionContext(graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(backend.deprovisionedSpecs()).hasSize(1);
        assertThat(backend.deprovisionedSpecs().get(0)).isEqualTo(spec);
    }

    // --- approval flow tests ---

    @Test
    void evaluatorRequiresApprovalReturnsPendingApproval() {
        var backend = new TrackingBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "k8s_namespace.production", "destroy namespace")),
                RiskClassification.HIGH, "Destroy namespace", null);
        backend.willReturnPlan(plan);
        var planStore = new InMemoryPlanStore();
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));
        var provisioner = new InfraNodeProvisioner(List.of(backend), evaluator, planStore);

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");
        var graph = graphOf(node);

        ProvisionResult result = provisioner.provision(node, provisionContext(graph));

        assertThat(result).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pending = (ProvisionResult.PendingApproval) result;
        assertThat(pending.nodeId()).isEqualTo(NODE_1);
        assertThat(pending.planReference()).isNotNull();
        assertThat(planStore.retrieve(pending.planReference())).isPresent();
        // Backend should NOT have been called for provision
        assertThat(backend.provisionedSpecs()).isEmpty();
    }

    @Test
    void evaluatorAutoApprovesProvisionsDirect() {
        var backend = new TrackingBackend("terraform");
        var planStore = new InMemoryPlanStore();
        var provisioner = new InfraNodeProvisioner(
                List.of(backend), new AlwaysAutoApproveEvaluator(), planStore);

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");
        var graph = graphOf(node);

        ProvisionResult result = provisioner.provision(node, provisionContext(graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(backend.provisionedSpecs()).hasSize(1);
    }

    @Test
    void reEntryWithValidApprovalProvisions() {
        var backend = new TrackingBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "k8s_namespace.production", "destroy namespace")),
                RiskClassification.HIGH, "Destroy namespace", null);
        backend.willReturnPlan(plan);
        var planStore = new InMemoryPlanStore();
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));
        var provisioner = new InfraNodeProvisioner(List.of(backend), evaluator, planStore);

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");
        var graph = graphOf(node);

        // First call — PendingApproval
        var firstResult = provisioner.provision(node, provisionContext(graph));
        assertThat(firstResult).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pending = (ProvisionResult.PendingApproval) firstResult;
        String planRef = pending.planReference();

        // Re-entry with approval
        var approval = new PlanApproval(planRef, "admin", Instant.now());
        var reEntryContext = new ProvisionContext("tenant-1", graph, approval);

        var result = provisioner.provision(node, reEntryContext);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        // Backend should have been called with APPLY phase and the approvedPlan
        assertThat(backend.provisionedSpecs()).hasSize(1);
        assertThat(backend.provisionContexts()).hasSize(1);
        var infraCtx = backend.provisionContexts().get(0);
        assertThat(infraCtx.phase()).isEqualTo(io.casehub.ops.api.infra.context.ProvisionPhase.APPLY);
        assertThat(infraCtx.approvedPlan()).isEqualTo(plan);
        // Plan should be removed after success
        assertThat(planStore.retrieve(planRef)).isEmpty();
    }

    @Test
    void reEntryWithStaleSpecReEvaluates() {
        var backend = new TrackingBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "k8s_namespace.production", "destroy namespace")),
                RiskClassification.HIGH, "Destroy namespace", null);
        backend.willReturnPlan(plan);
        var planStore = new InMemoryPlanStore();
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));
        var provisioner = new InfraNodeProvisioner(List.of(backend), evaluator, planStore);

        var originalSpec = new K8sNamespaceSpec("production", Labels.empty());
        var originalNode = infraNode(NODE_1, originalSpec, "terraform");
        var graph = graphOf(originalNode);

        // First call — PendingApproval
        var firstResult = provisioner.provision(originalNode, provisionContext(graph));
        assertThat(firstResult).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pending = (ProvisionResult.PendingApproval) firstResult;
        String originalRef = pending.planReference();

        // Spec changed
        var changedSpec = new K8sNamespaceSpec("staging", Labels.empty());
        var changedNode = infraNode(NODE_1, changedSpec, "terraform");

        var approval = new PlanApproval(originalRef, "admin", Instant.now());
        var reEntryContext = new ProvisionContext("tenant-1", graph, approval);

        var result = provisioner.provision(changedNode, reEntryContext);

        // Should get new PendingApproval since spec changed and plan is still HIGH risk
        assertThat(result).isInstanceOf(ProvisionResult.PendingApproval.class);
        var newPending = (ProvisionResult.PendingApproval) result;
        // Original plan removed
        assertThat(planStore.retrieve(originalRef)).isEmpty();
        // New plan stored
        assertThat(newPending.planReference()).isNotEqualTo(originalRef);
        assertThat(planStore.retrieve(newPending.planReference())).isPresent();
    }

    @Test
    void deprovisionApprovalFlow() {
        var backend = new TrackingBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "k8s_namespace.production", "destroy namespace")),
                RiskClassification.HIGH, "Destroy namespace", null);
        backend.willReturnPlan(plan);
        var planStore = new InMemoryPlanStore();
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));
        var provisioner = new InfraNodeProvisioner(List.of(backend), evaluator, planStore);

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");
        var graph = graphOf(node);

        // Deprovision — should require approval
        var deprovResult = provisioner.deprovision(node, deprovisionContext(graph));
        assertThat(deprovResult).isInstanceOf(DeprovisionResult.PendingApproval.class);
        var pending = (DeprovisionResult.PendingApproval) deprovResult;
        String planRef = pending.planReference();
        assertThat(planStore.retrieve(planRef)).isPresent();

        // Re-entry with approval
        var approval = new PlanApproval(planRef, "admin", Instant.now());
        var reEntryContext = new DeprovisionContext("tenant-1", graph, approval);

        var result = provisioner.deprovision(node, reEntryContext);

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(planStore.retrieve(planRef)).isEmpty();
        // Verify backend received the approved plan
        assertThat(backend.deprovisionContexts()).hasSize(1);
        var infraCtx = backend.deprovisionContexts().get(0);
        assertThat(infraCtx.approvedPlan()).isEqualTo(plan);
    }
}
