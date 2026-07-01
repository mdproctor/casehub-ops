package io.casehub.ops.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalThresholds;
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
import io.casehub.ops.api.infra.state.DriftReport;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.types.Labels;
import io.smallrye.mutiny.Uni;

class InfraApprovalEvaluatorTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final Instant NOW = Instant.now();

    // --- PlanningBackend: configurable plan() result ---

    static class PlanningBackend implements InfraBackend {

        private final String id;
        private Optional<ProvisionPlan> planResult = Optional.empty();

        PlanningBackend(String id) {
            this.id = id;
        }

        void willReturnPlan(ProvisionPlan plan) {
            this.planResult = Optional.of(plan);
        }

        void willReturnEmptyPlan() {
            this.planResult = Optional.empty();
        }

        @Override
        public String backendId() {
            return id;
        }

        @Override
        public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(new BackendProvisionResult.Provisioned(
                    new ResourceState(NODE_1, "k8s_namespace", ResourceStatus.HEALTHY,
                            NOW, null, ResourceOutputs.empty())));
        }

        @Override
        public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(new BackendDeprovisionResult.Deprovisioned(NODE_1));
        }

        @Override
        public Uni<ResourceState> readState(NodeId nodeId) {
            return Uni.createFrom().item(new ResourceState(
                    nodeId, "generic", ResourceStatus.HEALTHY, NOW, null, ResourceOutputs.empty()));
        }

        @Override
        public Uni<DriftReport> detectDrift(NodeId nodeId) {
            return Uni.createFrom().item(new DriftReport(
                    nodeId, false, List.of(), NOW, id));
        }

        @Override
        public Uni<Optional<ProvisionPlan>> plan(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(planResult);
        }
    }

    // --- tests ---

    @Test
    void backendReturnsEmptyPlanAutoApproves() {
        var backend = new PlanningBackend("terraform");
        backend.willReturnEmptyPlan();
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void backendReturnsLowRiskPlanAutoApproves() {
        var backend = new PlanningBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.ADD, "k8s_namespace.production", "create namespace")),
                RiskClassification.LOW, "Create namespace", null);
        backend.willReturnPlan(plan);
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void backendReturnsHighRiskPlanRequiresApproval() {
        var backend = new PlanningBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "k8s_namespace.production", "destroy namespace")),
                RiskClassification.HIGH, "Destroy namespace", null);
        backend.willReturnPlan(plan);
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.HIGH);
        assertThat(req.plan().detail()).isInstanceOf(ProvisionPlan.class);
        assertThat(req.plan().originalSpec()).isEqualTo(node.spec());
        assertThat(req.plan().summary()).isEqualTo("Destroy namespace");
    }

    @Test
    void nonInfraDesiredNodeSpecAutoApproves() {
        var backend = new PlanningBackend("terraform");
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        NodeSpec rawSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("x-1"), NodeType.of("unknown"), rawSpec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void criticalRiskPlanRequiresApproval() {
        var backend = new PlanningBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "vpc.main", "destroy VPC")),
                RiskClassification.CRITICAL, "Destroy VPC", null);
        backend.willReturnPlan(plan);
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
    }

    @Test
    void deprovisionActionPassedToBackend() {
        var backend = new PlanningBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.DESTROY, "k8s_namespace.staging", "destroy")),
                RiskClassification.HIGH, "Deprovision namespace", null);
        backend.willReturnPlan(plan);
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        var spec = new K8sNamespaceSpec("staging", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
    }

    @Test
    void mediumRiskBelowHighThresholdAutoApproves() {
        var backend = new PlanningBackend("terraform");
        var plan = new ProvisionPlan(NODE_1,
                List.of(new PlannedChange(ChangeAction.MODIFY, "k8s_namespace.staging", "update labels")),
                RiskClassification.MEDIUM, "Update namespace labels", null);
        backend.willReturnPlan(plan);
        var evaluator = new InfraApprovalEvaluator(
                List.of(backend), new ApprovalThresholds(RiskClassification.HIGH));

        var spec = new K8sNamespaceSpec("staging", Labels.empty());
        var node = infraNode(NODE_1, spec, "terraform");

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    // --- helpers ---

    private static DesiredNode infraNode(NodeId nodeId, InfraNodeSpec resourceSpec, String backendId) {
        return new DesiredNode(nodeId, NodeType.of(resourceSpec.resourceType()),
                new InfraDesiredNodeSpec(resourceSpec, backendId), false);
    }
}
