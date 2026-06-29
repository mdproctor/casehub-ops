package io.casehub.ops.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.CaseTypeNodeSpec;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.api.deployment.GoalEntry;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;

class DeploymentGoalCompilerTest {

    private DeploymentGoalCompiler compiler;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        compiler = new DeploymentGoalCompiler();
        factory = new io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory();
    }

    @Test
    void compilesAgentNode() {
        var agent = testAgent("agent-1");
        var goals = new DeploymentGoals(
                List.of(new GoalEntry<>(agent, List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("agent-1"));
        assertThat(node.id()).isEqualTo(NodeId.of("agent-1"));
        assertThat(node.type().value()).isEqualTo("agent");
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.spec()).isInstanceOf(AgentNodeSpec.class);
        assertThat(((AgentNodeSpec) node.spec()).agentId()).isEqualTo("agent-1");
    }

    @Test
    void compilesChannelNode() {
        var channel = testChannel("work-queue");
        var goals = new DeploymentGoals(
                List.of(),
                List.of(new GoalEntry<>(channel, List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("work-queue"));
        assertThat(node.id()).isEqualTo(NodeId.of("work-queue"));
        assertThat(node.type().value()).isEqualTo("channel");
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.spec()).isInstanceOf(ChannelNodeSpec.class);
    }

    @Test
    void compilesCaseTypeNode() {
        var caseType = new CaseTypeNodeSpec(
                "casehub",
                "issue",
                "1.0",
                "Issue Tracking",
                null,
                null,
                null);
        var goals = new DeploymentGoals(
                List.of(),
                List.of(),
                List.of(new GoalEntry<>(caseType, List.of())),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("casehub:issue:1.0"));
        assertThat(node.id()).isEqualTo(NodeId.of("casehub:issue:1.0"));
        assertThat(node.type().value()).isEqualTo("case_type");
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.spec()).isInstanceOf(CaseTypeNodeSpec.class);
    }

    @Test
    void compilesTrustPolicyNode() {
        var trustPolicy = new TrustPolicyNodeSpec(
                "code-review",
                0.7,
                5,
                0.05,
                0.3,
                null,
                false);
        var goals = new DeploymentGoals(
                List.of(),
                List.of(),
                List.of(),
                List.of(new GoalEntry<>(trustPolicy, List.of())),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("code-review"));
        assertThat(node.id()).isEqualTo(NodeId.of("code-review"));
        assertThat(node.type().value()).isEqualTo("trust_policy");
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.spec()).isInstanceOf(TrustPolicyNodeSpec.class);
    }

    @Test
    void explicitDependsOnCreatesDependencyEdges() {
        var agent = testAgent("agent-1");
        var channel = testChannel("work-queue");
        var goals = new DeploymentGoals(
                List.of(new GoalEntry<>(agent, List.of("work-queue"))),
                List.of(new GoalEntry<>(channel, List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies()).hasSize(1);

        var dep = graph.dependencies().iterator().next();
        assertThat(dep.from()).isEqualTo(NodeId.of("agent-1"));
        assertThat(dep.to()).isEqualTo(NodeId.of("work-queue"));
    }

    @Test
    void compilesAllFourTypesInOneGraph() {
        var agent = testAgent("agent-1");
        var channel = testChannel("work-queue");
        var caseType = new CaseTypeNodeSpec(
                "casehub",
                "issue",
                "1.0",
                "Issue Tracking",
                null,
                null,
                null);
        var trustPolicy = new TrustPolicyNodeSpec(
                "code-review",
                0.7,
                5,
                0.05,
                0.3,
                null,
                false);

        var goals = new DeploymentGoals(
                List.of(new GoalEntry<>(agent, List.of("work-queue"))),
                List.of(new GoalEntry<>(channel, List.of())),
                List.of(new GoalEntry<>(caseType, List.of())),
                List.of(new GoalEntry<>(trustPolicy, List.of())),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.dependencies()).hasSize(1);

        assertThat(graph.nodes()).containsKeys(
                NodeId.of("agent-1"),
                NodeId.of("casehub:issue:1.0"),
                NodeId.of("code-review"),
                NodeId.of("work-queue")
        );
    }

    @Test
    void emptyGoalsProducesEmptyGraph() {
        var goals = new DeploymentGoals(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.dependencies()).isEmpty();
    }

    @Test
    void compilesCaseTypeWithDefinitionFile() {
        var caseType = new CaseTypeNodeSpec(
                "io.casehub.devtown", "pr-review", "1.0",
                "PR Review", "Automated",
                "test-case-defs/pr-review.yaml", null);
        var goals = new DeploymentGoals(
                List.of(), List.of(),
                List.of(new GoalEntry<>(caseType, List.of())),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        DesiredNode node = graph.nodes().get(NodeId.of("io.casehub.devtown:pr-review:1.0"));
        assertThat(node).isNotNull();
        var resolved = (CaseTypeNodeSpec) node.spec();
        assertThat(resolved.definitionPayload()).isNotNull();
        assertThat(resolved.definitionPayload()).containsEntry("namespace", "io.casehub.devtown");
        assertThat(resolved.definitionFile()).isEqualTo("test-case-defs/pr-review.yaml");
    }

    @Test
    void skipsResolutionWhenPayloadAlreadySet() {
        var payload = java.util.Map.<String, Object>of("namespace", "pre-set");
        var caseType = new CaseTypeNodeSpec(
                "io.casehub.devtown", "pr-review", "1.0",
                "PR Review", "Automated",
                "test-case-defs/pr-review.yaml", payload);
        var goals = new DeploymentGoals(
                List.of(), List.of(),
                List.of(new GoalEntry<>(caseType, List.of())),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        var resolved = (CaseTypeNodeSpec) graph.nodes()
                .get(NodeId.of("io.casehub.devtown:pr-review:1.0")).spec();
        assertThat(resolved.definitionPayload()).containsEntry("namespace", "pre-set");
    }

    @Test
    void caseTypeWithoutDefinitionFilePassesThrough() {
        var caseType = new CaseTypeNodeSpec(
                "casehub", "issue", "1.0",
                "Issue Tracking", null,
                null, null);
        var goals = new DeploymentGoals(
                List.of(), List.of(),
                List.of(new GoalEntry<>(caseType, List.of())),
                List.of(),
                List.of(),
                List.of());

        DesiredStateGraph graph = compiler.compile(goals, factory);

        var resolved = (CaseTypeNodeSpec) graph.nodes()
                .get(NodeId.of("casehub:issue:1.0")).spec();
        assertThat(resolved.definitionPayload()).isNull();
        assertThat(resolved.definitionFile()).isNull();
    }

    private AgentNodeSpec testAgent(String id) {
        return new AgentNodeSpec(id, "Test Agent", "worker",
                "anthropic", "claude", "opus-4", "1.0", null,
                null, null, null, null,
                List.of(new AgentCapability("code-review", null, null, null, null, null, null, null, null)),
                new AgentDisposition("collaborative", "strict", null, null, null, false),
                null, null, null, List.of());
    }

    private ChannelNodeSpec testChannel(String name) {
        return new ChannelNodeSpec(name, null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null,
                null, null, null, null);
    }
}

