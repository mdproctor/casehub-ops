package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceGoalCompilerTest {

    private ComplianceGoalCompiler compiler;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        compiler = new ComplianceGoalCompiler();
        factory = new DefaultDesiredStateGraphFactory();
    }

    private DesiredStateGraph compileToGraph(ComplianceGoals goals) {
        var result = compiler.compile(goals, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        return ((CompilationResult.SingleGraph) result).graph();
    }

    @Test
    void compilesControlNode() {
        var spec = new ComplianceControlSpec(
                "encryption-at-rest", "ENCRYPTION_AT_REST", "FILE_EXISTENCE",
                "Encryption", "AES-256",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of("cipher", "AES-256"));
        var goals = new ComplianceGoals(
                List.of(new ComplianceGoalEntry(spec, List.of())));

        DesiredStateGraph graph = compileToGraph(goals);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("encryption-at-rest"));
        assertThat(node.id()).isEqualTo(NodeId.of("encryption-at-rest"));
        assertThat(node.type().value()).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.spec()).isInstanceOf(ComplianceControlSpec.class);
    }

    @Test
    void humanReviewControlSetsRequiresHuman() {
        var spec = new ComplianceControlSpec(
                "access-review", "ACCESS_REVIEW", "FILE_EXISTENCE",
                "Access Review", "Quarterly",
                List.of(), 90, true, Map.of());
        var goals = new ComplianceGoals(
                List.of(new ComplianceGoalEntry(spec, List.of())));

        DesiredStateGraph graph = compileToGraph(goals);

        assertThat(graph.nodes().get(NodeId.of("access-review")).requiresHuman()).isTrue();
    }

    @Test
    void dependsOnCreatesDependencyEdges() {
        var spec1 = new ComplianceControlSpec(
                "data-processing", "DATA_PROCESSING", "FILE_EXISTENCE",
                "DP", "Records", List.of(), 30, false, Map.of());
        var spec2 = new ComplianceControlSpec(
                "ai-risk", "AI_RISK_ASSESSMENT", "FILE_EXISTENCE",
                "AI", "Risk", List.of(), 365, true, Map.of());
        var goals = new ComplianceGoals(List.of(
                new ComplianceGoalEntry(spec1, List.of()),
                new ComplianceGoalEntry(spec2, List.of("data-processing"))));

        DesiredStateGraph graph = compileToGraph(goals);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies()).hasSize(1);
        var dep = graph.dependencies().iterator().next();
        assertThat(dep.from()).isEqualTo(NodeId.of("ai-risk"));
        assertThat(dep.to()).isEqualTo(NodeId.of("data-processing"));
    }

    @Test
    void emptyGoalsProducesEmptyGraph() {
        var goals = new ComplianceGoals(List.of());
        DesiredStateGraph graph = compileToGraph(goals);
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.dependencies()).isEmpty();
    }

    @Test
    void compilesAllControlTypes() {
        var goals = new ComplianceGoalLoader().load("test-compliance/all-controls.yaml");
        DesiredStateGraph graph = compileToGraph(goals);
        assertThat(graph.nodes()).hasSize(8);
        assertThat(graph.dependencies()).hasSize(1);
    }
}
