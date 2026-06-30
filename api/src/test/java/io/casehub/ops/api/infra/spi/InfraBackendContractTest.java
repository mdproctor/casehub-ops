package io.casehub.ops.api.infra.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.GenericResourceSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.context.ProvisionAction;
import io.casehub.ops.api.infra.context.ProvisionPhase;
import io.casehub.ops.api.approval.ApprovalThresholds;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.goal.ImportDeclaration;
import io.casehub.ops.api.infra.goal.InfraGoals;
import io.casehub.ops.api.infra.goal.ResourceDeclaration;
import io.casehub.ops.api.infra.plan.ChangeAction;
import io.casehub.ops.api.infra.plan.FieldDiff;
import io.casehub.ops.api.infra.plan.PlannedChange;
import io.casehub.ops.api.infra.plan.ProvisionPlan;
import io.casehub.ops.api.infra.plan.ToolPlanDetail;
import io.casehub.ops.api.infra.state.DriftReport;
import io.casehub.ops.api.infra.state.DriftedField;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.task.ArtifactProvenance;
import io.casehub.ops.api.infra.task.ArtifactType;
import io.casehub.ops.api.infra.task.ExecutionArtifact;
import io.casehub.ops.api.infra.task.ProvisionOutcome;
import io.casehub.ops.api.infra.task.ProvisionTask;
import io.casehub.ops.api.infra.task.TaskAction;
import io.casehub.ops.api.infra.types.Labels;
import io.smallrye.mutiny.Uni;

class InfraBackendContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NodeId TEST_NODE_ID = NodeId.of("test-node-1");
    private static final Instant NOW = Instant.now();

    // ---- StubBackend ----

    static class StubBackend implements InfraBackend {

        @Override
        public String backendId() {
            return "stub";
        }

        @Override
        public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context) {
            var state = new ResourceState(
                    context.nodeId(), spec.resourceType(), ResourceStatus.HEALTHY,
                    Instant.now(), null, ResourceOutputs.empty());
            return Uni.createFrom().item(new BackendProvisionResult.Provisioned(state));
        }

        @Override
        public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(new BackendDeprovisionResult.Deprovisioned(context.nodeId()));
        }

        @Override
        public Uni<ResourceState> readState(NodeId nodeId) {
            return Uni.createFrom().item(new ResourceState(
                    nodeId, "generic_resource", ResourceStatus.HEALTHY,
                    Instant.now(), null, ResourceOutputs.empty()));
        }

        @Override
        public Uni<DriftReport> detectDrift(NodeId nodeId) {
            return Uni.createFrom().item(new DriftReport(
                    nodeId, false, List.of(), Instant.now(), "stub"));
        }

        @Override
        public Uni<Optional<ProvisionPlan>> plan(InfraNodeSpec spec, InfraProvisionContext context) {
            return Uni.createFrom().item(Optional.empty());
        }
    }

    // ---- InfraBackend SPI tests ----

    @Test
    void provision_returnsProvisionedWithCorrectState() {
        var backend = new StubBackend();
        var spec = new K8sNamespaceSpec("test-ns", Labels.empty());
        var context = planContext();

        BackendProvisionResult result = backend.provision(spec, context).await().indefinitely();

        assertThat(result).isInstanceOf(BackendProvisionResult.Provisioned.class);
        var provisioned = (BackendProvisionResult.Provisioned) result;
        assertThat(provisioned.state().nodeId()).isEqualTo(TEST_NODE_ID);
        assertThat(provisioned.state().resourceType()).isEqualTo("k8s_namespace");
        assertThat(provisioned.state().status()).isEqualTo(ResourceStatus.HEALTHY);
    }

    @Test
    void deprovision_returnsDeprovisionedWithCorrectNodeId() {
        var backend = new StubBackend();
        var spec = new K8sNamespaceSpec("test-ns", Labels.empty());
        var context = planContext();

        BackendDeprovisionResult result = backend.deprovision(spec, context).await().indefinitely();

        assertThat(result).isInstanceOf(BackendDeprovisionResult.Deprovisioned.class);
        var deprovisioned = (BackendDeprovisionResult.Deprovisioned) result;
        assertThat(deprovisioned.nodeId()).isEqualTo(TEST_NODE_ID);
    }

    @Test
    void readState_returnsHealthyState() {
        var backend = new StubBackend();

        ResourceState state = backend.readState(TEST_NODE_ID).await().indefinitely();

        assertThat(state.nodeId()).isEqualTo(TEST_NODE_ID);
        assertThat(state.status()).isEqualTo(ResourceStatus.HEALTHY);
        assertThat(state.resourceType()).isEqualTo("generic_resource");
    }

    @Test
    void detectDrift_returnsNonDrifted() {
        var backend = new StubBackend();

        DriftReport report = backend.detectDrift(TEST_NODE_ID).await().indefinitely();

        assertThat(report.nodeId()).isEqualTo(TEST_NODE_ID);
        assertThat(report.drifted()).isFalse();
        assertThat(report.drifts()).isEmpty();
        assertThat(report.backendId()).isEqualTo("stub");
    }

    @Test
    void plan_returnsEmpty() {
        var backend = new StubBackend();
        var spec = new K8sNamespaceSpec("test-ns", Labels.empty());
        var context = planContext();

        Optional<ProvisionPlan> plan = backend.plan(spec, context).await().indefinitely();

        assertThat(plan).isEmpty();
    }

    // ---- Sealed result pattern matching ----

    @Test
    void backendProvisionResult_sealedPatternMatching() {
        var state = new ResourceState(
                TEST_NODE_ID, "test", ResourceStatus.HEALTHY,
                NOW, null, ResourceOutputs.empty());

        BackendProvisionResult success = new BackendProvisionResult.Provisioned(state);
        BackendProvisionResult failure = new BackendProvisionResult.Failed("timeout", true);

        assertThat(describeProvisionResult(success)).isEqualTo("provisioned:test-node-1");
        assertThat(describeProvisionResult(failure)).isEqualTo("failed:timeout:retryable");
    }

    @Test
    void backendDeprovisionResult_sealedPatternMatching() {
        BackendDeprovisionResult success = new BackendDeprovisionResult.Deprovisioned(TEST_NODE_ID);
        BackendDeprovisionResult failure = new BackendDeprovisionResult.Failed("in-use", false);

        String successDesc = switch (success) {
            case BackendDeprovisionResult.Deprovisioned d -> "deprovisioned:" + d.nodeId().value();
            case BackendDeprovisionResult.Failed f -> "failed:" + f.reason();
        };
        String failureDesc = switch (failure) {
            case BackendDeprovisionResult.Deprovisioned d -> "deprovisioned:" + d.nodeId().value();
            case BackendDeprovisionResult.Failed f -> "failed:" + f.reason() + (f.retryable() ? ":retryable" : "");
        };

        assertThat(successDesc).isEqualTo("deprovisioned:test-node-1");
        assertThat(failureDesc).isEqualTo("failed:in-use");
    }

    // ---- ResourceOutputs tests ----

    @Test
    void resourceOutputs_getReturnsValue() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ip", "10.0.0.1");
        node.put("port", 8080);

        var outputs = new ResourceOutputs(Map.of("ip", node.get("ip"), "port", node.get("port")));

        assertThat(outputs.get("ip")).isPresent();
        assertThat(outputs.get("missing")).isEmpty();
    }

    @Test
    void resourceOutputs_getStringReturnsTextValue() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("host", "example.com");
        node.put("port", 8080);

        var outputs = new ResourceOutputs(Map.of("host", node.get("host"), "port", node.get("port")));

        assertThat(outputs.getString("host")).hasValue("example.com");
        assertThat(outputs.getString("port")).isEmpty(); // not textual
        assertThat(outputs.getString("missing")).isEmpty();
    }

    @Test
    void resourceOutputs_empty() {
        var empty = ResourceOutputs.empty();

        assertThat(empty.values()).isEmpty();
        assertThat(empty.get("any")).isEmpty();
        assertThat(empty.getString("any")).isEmpty();
    }

    @Test
    void resourceOutputs_defensiveCopy() {
        var mutable = new HashMap<>(Map.of("key", (JsonNode) MAPPER.createObjectNode().put("v", "1")));
        var outputs = new ResourceOutputs(mutable);
        mutable.put("injected", MAPPER.createObjectNode());

        assertThat(outputs.get("injected")).isEmpty();
    }

    @Test
    void resourceOutputs_nullTreatedAsEmpty() {
        var outputs = new ResourceOutputs(null);

        assertThat(outputs.values()).isEmpty();
    }

    // ---- DriftReport tests ----

    @Test
    void driftReport_defensiveCopyOnDrifts() {
        var drifts = new ArrayList<>(List.of(
                new DriftedField("cpu", "2", "4")));
        var report = new DriftReport(TEST_NODE_ID, true, drifts, NOW, "terraform");
        drifts.add(new DriftedField("injected", "a", "b"));

        assertThat(report.drifts()).hasSize(1);
        assertThat(report.drifts().get(0).field()).isEqualTo("cpu");
    }

    @Test
    void driftReport_requiresNodeIdDetectedAtBackendId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DriftReport(null, false, List.of(), NOW, "tf"));
        assertThatNullPointerException()
                .isThrownBy(() -> new DriftReport(TEST_NODE_ID, false, List.of(), null, "tf"));
        assertThatNullPointerException()
                .isThrownBy(() -> new DriftReport(TEST_NODE_ID, false, List.of(), NOW, null));
    }

    // ---- InfraProvisionContext tests ----

    @Test
    void infraProvisionContext_planPhase() {
        var context = planContext();

        assertThat(context.nodeId()).isEqualTo(TEST_NODE_ID);
        assertThat(context.phase()).isEqualTo(ProvisionPhase.PLAN);
        assertThat(context.action()).isEqualTo(ProvisionAction.PROVISION);
        assertThat(context.approvedPlan()).isNull();
    }

    @Test
    void infraProvisionContext_applyPhaseWithPlan() {
        var plan = new ProvisionPlan(
                TEST_NODE_ID,
                List.of(new PlannedChange(ChangeAction.ADD, "aws_instance.web", "new resource")),
                RiskClassification.LOW,
                "Add one EC2 instance",
                null);

        var context = new InfraProvisionContext(
                TEST_NODE_ID, "tenant-1", ProvisionPhase.APPLY, ProvisionAction.PROVISION,
                plan, new ApprovalThresholds(RiskClassification.MEDIUM), NOW);

        assertThat(context.phase()).isEqualTo(ProvisionPhase.APPLY);
        assertThat(context.approvedPlan()).isNotNull();
        assertThat(context.approvedPlan().changes()).hasSize(1);
    }

    @Test
    void infraProvisionContext_requiresNonNullFields() {
        var thresholds = new ApprovalThresholds(RiskClassification.LOW);

        assertThatNullPointerException()
                .isThrownBy(() -> new InfraProvisionContext(null, "t", ProvisionPhase.PLAN,
                        ProvisionAction.PROVISION, null, thresholds, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> new InfraProvisionContext(TEST_NODE_ID, null, ProvisionPhase.PLAN,
                        ProvisionAction.PROVISION, null, thresholds, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> new InfraProvisionContext(TEST_NODE_ID, "t", null,
                        ProvisionAction.PROVISION, null, thresholds, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> new InfraProvisionContext(TEST_NODE_ID, "t", ProvisionPhase.PLAN,
                        null, null, thresholds, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> new InfraProvisionContext(TEST_NODE_ID, "t", ProvisionPhase.PLAN,
                        ProvisionAction.PROVISION, null, null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> new InfraProvisionContext(TEST_NODE_ID, "t", ProvisionPhase.PLAN,
                        ProvisionAction.PROVISION, null, thresholds, null));
    }

    // ---- ResourceState tests ----

    @Test
    void resourceState_requiresNonNullFields() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResourceState(null, "t", ResourceStatus.HEALTHY,
                        NOW, null, ResourceOutputs.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new ResourceState(TEST_NODE_ID, null, ResourceStatus.HEALTHY,
                        NOW, null, ResourceOutputs.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new ResourceState(TEST_NODE_ID, "t", null,
                        NOW, null, ResourceOutputs.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new ResourceState(TEST_NODE_ID, "t", ResourceStatus.HEALTHY,
                        null, null, ResourceOutputs.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new ResourceState(TEST_NODE_ID, "t", ResourceStatus.HEALTHY,
                        NOW, null, null));
    }

    @Test
    void resourceState_attributesNullable() {
        var state = new ResourceState(TEST_NODE_ID, "test", ResourceStatus.HEALTHY,
                NOW, null, ResourceOutputs.empty());

        assertThat(state.attributes()).isNull();
    }

    @Test
    void resourceStatus_allValues() {
        assertThat(ResourceStatus.values()).containsExactly(
                ResourceStatus.HEALTHY, ResourceStatus.DRIFTED, ResourceStatus.DEGRADED,
                ResourceStatus.UNAVAILABLE, ResourceStatus.PROVISIONING, ResourceStatus.UNKNOWN);
    }

    // ---- ProvisionPlan tests ----

    @Test
    void provisionPlan_defensiveCopyOnChanges() {
        var changes = new ArrayList<>(List.of(
                new PlannedChange(ChangeAction.ADD, "aws_instance.web", "new")));
        var plan = new ProvisionPlan(TEST_NODE_ID, changes, RiskClassification.LOW, "Add instance", null);
        changes.add(new PlannedChange(ChangeAction.DESTROY, "injected", "bad"));

        assertThat(plan.changes()).hasSize(1);
    }

    @Test
    void provisionPlan_requiresNonNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ProvisionPlan(null, List.of(), RiskClassification.LOW, "summary", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProvisionPlan(TEST_NODE_ID, List.of(), null, "summary", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProvisionPlan(TEST_NODE_ID, List.of(), RiskClassification.LOW, null, null));
    }

    @Test
    void toolPlanDetail_sealedPatternMatching() {
        ObjectNode planJson = MAPPER.createObjectNode();
        planJson.put("format_version", "1.2");

        ToolPlanDetail terraform = new ToolPlanDetail.TerraformPlanDetail(planJson);
        ToolPlanDetail ansible = new ToolPlanDetail.AnsibleCheckDetail("ok=2 changed=0");
        ToolPlanDetail standalone = new ToolPlanDetail.StandaloneDiffDetail(
                List.of(new FieldDiff("cpu", "2", "4")));

        assertThat(switch (terraform) {
            case ToolPlanDetail.TerraformPlanDetail t -> "tf:" + t.planJson().get("format_version").asText();
            case ToolPlanDetail.AnsibleCheckDetail a -> "ansible";
            case ToolPlanDetail.StandaloneDiffDetail s -> "standalone";
        }).isEqualTo("tf:1.2");

        assertThat(switch (ansible) {
            case ToolPlanDetail.TerraformPlanDetail t -> "tf";
            case ToolPlanDetail.AnsibleCheckDetail a -> "ansible:" + a.checkOutput();
            case ToolPlanDetail.StandaloneDiffDetail s -> "standalone";
        }).isEqualTo("ansible:ok=2 changed=0");

        assertThat(switch (standalone) {
            case ToolPlanDetail.TerraformPlanDetail t -> "tf";
            case ToolPlanDetail.AnsibleCheckDetail a -> "ansible";
            case ToolPlanDetail.StandaloneDiffDetail s -> "standalone:" + s.diffs().size();
        }).isEqualTo("standalone:1");
    }

    @Test
    void standaloneDiffDetail_defensiveCopy() {
        var diffs = new ArrayList<>(List.of(new FieldDiff("f", "a", "b")));
        var detail = new ToolPlanDetail.StandaloneDiffDetail(diffs);
        diffs.add(new FieldDiff("injected", "x", "y"));

        assertThat(detail.diffs()).hasSize(1);
    }

    // ---- ProvisionTask / ProvisionOutcome tests ----

    @Test
    void provisionTask_currentStateNullableForCreate() {
        var nodeId = NodeId.of("task-node");
        var spec = new K8sNamespaceSpec("ns", Labels.empty());
        var task = new ProvisionTask(nodeId, spec, TaskAction.CREATE, null);

        assertThat(task.nodeId()).isEqualTo(nodeId);
        assertThat(task.spec()).isEqualTo(spec);
        assertThat(task.action()).isEqualTo(TaskAction.CREATE);
        assertThat(task.currentState()).isNull();
    }

    @Test
    void provisionTask_requiresNodeIdSpecAndAction() {
        var nodeId = NodeId.of("task-node");
        var spec = new K8sNamespaceSpec("ns", Labels.empty());
        assertThatNullPointerException()
                .isThrownBy(() -> new ProvisionTask(null, spec, TaskAction.CREATE, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProvisionTask(nodeId, null, TaskAction.CREATE, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ProvisionTask(nodeId, spec, null, null));
    }

    @Test
    void provisionOutcome_allFieldsNullable() {
        var outcome = new ProvisionOutcome(true, null, null, null);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.resultState()).isNull();
        assertThat(outcome.executionLog()).isNull();
        assertThat(outcome.artifact()).isNull();
    }

    @Test
    void artifactProvenance_sealedPatternMatching() {
        ArtifactProvenance llm = new ArtifactProvenance.LlmGenerated("claude-4", NOW, "abc123");
        ArtifactProvenance cached = new ArtifactProvenance.CachedReuse("def456", NOW);
        ArtifactProvenance hand = new ArtifactProvenance.HandWritten("ops-team");

        assertThat(switch (llm) {
            case ArtifactProvenance.LlmGenerated g -> "llm:" + g.model();
            case ArtifactProvenance.CachedReuse c -> "cached";
            case ArtifactProvenance.HandWritten h -> "hand";
        }).isEqualTo("llm:claude-4");

        assertThat(switch (cached) {
            case ArtifactProvenance.LlmGenerated g -> "llm";
            case ArtifactProvenance.CachedReuse c -> "cached:" + c.originalHash();
            case ArtifactProvenance.HandWritten h -> "hand";
        }).isEqualTo("cached:def456");

        assertThat(switch (hand) {
            case ArtifactProvenance.LlmGenerated g -> "llm";
            case ArtifactProvenance.CachedReuse c -> "cached";
            case ArtifactProvenance.HandWritten h -> "hand:" + h.author();
        }).isEqualTo("hand:ops-team");
    }

    // ---- InfraGoals tests ----

    @Test
    void infraGoals_defensiveCopies() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("ami", "ami-123");

        var resources = new ArrayList<>(List.of(
                new ResourceDeclaration("web", "compute_instance", null, config, List.of())));
        var imports = new ArrayList<>(List.of(
                new ImportDeclaration("vpc", "network", config, List.of())));

        var goals = new InfraGoals("terraform", resources, imports);
        resources.add(new ResourceDeclaration("injected", "bad", null, config, List.of()));
        imports.add(new ImportDeclaration("injected", "bad", config, List.of()));

        assertThat(goals.resources()).hasSize(1);
        assertThat(goals.imports()).hasSize(1);
    }

    @Test
    void infraGoals_defaultBackendNullable() {
        var goals = new InfraGoals(null, List.of(), List.of());
        assertThat(goals.defaultBackend()).isNull();
    }

    @Test
    void resourceDeclaration_defensiveCopyOnDependsOn() {
        var deps = new ArrayList<>(List.of("vpc"));
        var decl = new ResourceDeclaration("web", "compute", null, MAPPER.createObjectNode(), deps);
        deps.add("injected");

        assertThat(decl.dependsOn()).hasSize(1);
    }

    @Test
    void resourceDeclaration_backendNullable() {
        var decl = new ResourceDeclaration("web", "compute", null, MAPPER.createObjectNode(), List.of());
        assertThat(decl.backend()).isNull();
    }

    @Test
    void importDeclaration_defensiveCopyOnDependsOn() {
        var deps = new ArrayList<>(List.of("other"));
        var imp = new ImportDeclaration("vpc", "network", MAPPER.createObjectNode(), deps);
        deps.add("injected");

        assertThat(imp.dependsOn()).hasSize(1);
    }

    // ---- Helper ----

    private InfraProvisionContext planContext() {
        return new InfraProvisionContext(
                TEST_NODE_ID, "tenant-1", ProvisionPhase.PLAN, ProvisionAction.PROVISION,
                null, new ApprovalThresholds(RiskClassification.LOW), NOW);
    }

    private String describeProvisionResult(BackendProvisionResult result) {
        return switch (result) {
            case BackendProvisionResult.Provisioned p -> "provisioned:" + p.state().nodeId().value();
            case BackendProvisionResult.Failed f -> "failed:" + f.reason() + (f.retryable() ? ":retryable" : "");
        };
    }
}
