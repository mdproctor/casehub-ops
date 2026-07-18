package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.AdaptationActionSpec;
import io.casehub.ops.api.deployment.AdaptationRuleSpec;
import io.casehub.ops.api.deployment.AdaptationTrigger;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.api.deployment.GoalEntry;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.deployment.DeploymentGoalCompiler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptationRuleTest {

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromSpecsCreatesRules() {
        var compiler = new DeploymentGoalCompiler();
        var trigger = new AdaptationTrigger("high-load", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var ruleSpec = new AdaptationRuleSpec("scale-on-load", trigger, List.of(scaleAction));

        var rules = AdaptationRule.fromSpecs(List.of(ruleSpec), compiler, mapper, factory);

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).name()).isEqualTo("scale-on-load");
    }

    @Test
    void emptySpecsProducesEmptyRules() {
        var compiler = new DeploymentGoalCompiler();
        var rules = AdaptationRule.fromSpecs(List.of(), compiler, mapper, factory);
        assertThat(rules).isEmpty();
    }

    @Test
    void applyDispatchesToScaleAction() {
        var compiler = new DeploymentGoalCompiler();
        var trigger = new AdaptationTrigger("high-load", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var ruleSpec = new AdaptationRuleSpec("scale-on-load", trigger, List.of(scaleAction));

        var rule = AdaptationRule.fromSpecs(List.of(ruleSpec), compiler, mapper, factory).get(0);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("high-load", "_singleton", "test", 1.0, Map.of(), Instant.now(), Instant.now(), 0);

        var result = rule.apply(graph, situation);

        assertThat(result.nodes()).hasSize(5);
        assertThat(result.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3"),
            NodeId.of("risk-agent~4"),
            NodeId.of("risk-agent~5")
        );
    }

    @Test
    void applyDispatchesToUpdateAction() {
        var compiler = new DeploymentGoalCompiler();
        var trigger = new AdaptationTrigger("policy-change", 0.8, null, null);
        var updateAction = new AdaptationActionSpec.UpdateActionSpec(
            "risk-policy",
            "trust_policy",
            Map.of("threshold", 0.9)
        );
        var ruleSpec = new AdaptationRuleSpec("update-threshold", trigger, List.of(updateAction));

        var rule = AdaptationRule.fromSpecs(List.of(ruleSpec), compiler, mapper, factory).get(0);

        var originalSpec = new TrustPolicyNodeSpec(
            "risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), false
        );
        var node = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            originalSpec,
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(node), List.of());
        var situation = new ActiveSituation("policy-change", "_singleton", "test", 0.9, Map.of(), Instant.now(), Instant.now(), 0);

        var result = rule.apply(graph, situation);

        var updatedNode = result.nodes().get(NodeId.of("risk-policy"));
        assertThat(updatedNode).isNotNull();
        var updatedSpec = (TrustPolicyNodeSpec) updatedNode.spec();
        assertThat(updatedSpec.threshold()).isEqualTo(0.9);
    }

    @Test
    void applyDispatchesToAddAction() {
        var compiler = new DeploymentGoalCompiler();
        var trigger = new AdaptationTrigger("expand-capacity", 0.75, null, null);
        var newAgent = new AgentNodeSpec(
            "new-agent", "New Agent", "new-slot",
            "anthropic", "claude", "opus-4.6-2026",
            "1.0.0", "fp-xyz789",
            null, null, null, null,
            List.of(), null, null, null, null, List.of()
        );
        var addAction = new AdaptationActionSpec.AddActionSpec(
            new DeploymentGoals(
                List.of(new GoalEntry<>(newAgent, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of()
            )
        );
        var ruleSpec = new AdaptationRuleSpec("add-capacity", trigger, List.of(addAction));

        var rule = AdaptationRule.fromSpecs(List.of(ruleSpec), compiler, mapper, factory).get(0);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("expand-capacity", "_singleton", "test", 0.85, Map.of(), Instant.now(), Instant.now(), 0);

        var result = rule.apply(graph, situation);

        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("new-agent")
        );
    }

    @Test
    void targetNodeIdsReturnsAllAffectedNodes() {
        var compiler = new DeploymentGoalCompiler();
        var trigger = new AdaptationTrigger("high-load", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var updateAction = new AdaptationActionSpec.UpdateActionSpec(
            "risk-policy",
            null,
            Map.of("threshold", 0.9)
        );
        var ruleSpec = new AdaptationRuleSpec(
            "multi-action",
            trigger,
            List.of(scaleAction, updateAction)
        );

        var rule = AdaptationRule.fromSpecs(List.of(ruleSpec), compiler, mapper, factory).get(0);

        var baseNode = createAgentNode("risk-agent");
        var policyNode = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            new TrustPolicyNodeSpec("risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), true),
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(baseNode, policyNode), List.of());

        var targetIds = rule.targetNodeIds(graph);

        assertThat(targetIds).containsExactlyInAnyOrder(
            NodeId.of("risk-agent"),
            NodeId.of("risk-policy")
        );
    }

    @Test
    void multipleActionsAppliedInSequence() {
        var compiler = new DeploymentGoalCompiler();
        var trigger = new AdaptationTrigger("combined", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 3);
        var updateAction = new AdaptationActionSpec.UpdateActionSpec(
            "risk-policy",
            null,
            Map.of("threshold", 0.95)
        );
        var ruleSpec = new AdaptationRuleSpec(
            "combined-rule",
            trigger,
            List.of(scaleAction, updateAction)
        );

        var rule = AdaptationRule.fromSpecs(List.of(ruleSpec), compiler, mapper, factory).get(0);

        var baseNode = createAgentNode("risk-agent");
        var policyNode = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            new TrustPolicyNodeSpec("risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), true),
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(baseNode, policyNode), List.of());
        var situation = new ActiveSituation("combined", "_singleton", "test", 1.0, Map.of(), Instant.now(), Instant.now(), 0);

        var result = rule.apply(graph, situation);

        // Scale produced 3 instances
        assertThat(result.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3")
        );

        // Update changed threshold
        var updatedPolicy = result.nodes().get(NodeId.of("risk-policy"));
        assertThat(updatedPolicy).isNotNull();
        var updatedSpec = (TrustPolicyNodeSpec) updatedPolicy.spec();
        assertThat(updatedSpec.threshold()).isEqualTo(0.95);
    }

    private DesiredNode createAgentNode(String agentId) {
        var spec = new AgentNodeSpec(
            agentId, "Risk Agent", "risk-assessment",
            "anthropic", "claude", "opus-4.6-2026",
            "1.0.0", "fp-abc123",
            null, null, null, null,
            List.of(), null, null, null, null, List.of()
        );
        return new DesiredNode(NodeId.of(agentId), NodeType.of("agent"), spec, io.casehub.desiredstate.api.HumanGating.NONE);
    }
}
