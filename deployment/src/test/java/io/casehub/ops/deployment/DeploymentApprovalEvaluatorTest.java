package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentApprovalEvaluatorTest {

    private final DeploymentApprovalEvaluator evaluator = new DeploymentApprovalEvaluator();

    @Test
    void trustPolicyRequiresApproval() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.HIGH);
        assertThat(req.plan().summary()).contains("claims-routing");
        assertThat(req.plan().originalSpec()).isEqualTo(spec);
    }

    @Test
    void channelAutoApproves() {
        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch-1"), NodeType.of("channel"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");
        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void agentAutoApprovesWithDefaultThresholds() {
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", null, null, null, null, null, List.of(), null, null, null, null, List.of());
        var node = new DesiredNode(NodeId.of("a-1"), NodeType.of("agent"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");
        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void caseTypeAutoApproves() {
        var spec = new CaseTypeNodeSpec("ns", "name", "1.0", "Title", "Summary", null, null);
        var node = new DesiredNode(NodeId.of("ct-1"), NodeType.of("casetype"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");
        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void endpointAutoApproves() {
        var spec = new EndpointNodeSpec("/api/v1/claims", EndpointType.SERVICE,
                EndpointProtocol.HTTP, Map.of("url", "http://localhost:8080/api/v1/claims"),
                null, Set.of());
        var node = new DesiredNode(NodeId.of("ep-1"), NodeType.of("endpoint"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");
        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void nonDeploymentSpecAutoApproves() {
        NodeSpec unknownSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("x-1"), NodeType.of("unknown"), unknownSpec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");
        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void deprovisionTrustPolicyAlsoRequiresApproval() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");
        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
    }
}
