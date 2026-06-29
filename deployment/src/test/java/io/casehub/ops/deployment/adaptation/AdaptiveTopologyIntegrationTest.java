package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.ActiveSituation;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.SituationChangeEvent;
import io.casehub.desiredstate.api.SituationSource;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.deployment.DeploymentGoalCompiler;
import io.casehub.ops.deployment.DeploymentGoalLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: YAML parsing → adaptation rule compilation → graph mutation.
 * <p>
 * Validates the full adaptive ops pipeline from loading a YAML file with an
 * {@code adaptations:} section through creating an {@link AdaptiveTopologyManager}
 * and observing graph changes in response to mock situation changes.
 */
class AdaptiveTopologyIntegrationTest {

    private DeploymentGoalLoader loader;
    private DeploymentGoalCompiler compiler;
    private DesiredStateGraphFactory graphFactory;
    private ObjectMapper mapper;
    private StubSituationSource situationSource;
    private SpyReconciliationTarget reconciliationTarget;
    private AdaptiveTopologyManager manager;

    @BeforeEach
    void setUp() {
        loader = new DeploymentGoalLoader();
        compiler = new DeploymentGoalCompiler();
        graphFactory = new DefaultDesiredStateGraphFactory();
        mapper = new ObjectMapper();
        situationSource = new StubSituationSource();
        reconciliationTarget = new SpyReconciliationTarget();
        manager = new AdaptiveTopologyManager(
            compiler, graphFactory, mapper, situationSource, reconciliationTarget);
    }

    @Test
    void yamlParsingExtractsAdaptationRules() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        assertThat(goals.adaptations()).hasSize(2);
        assertThat(goals.adaptations().get(0).name()).isEqualTo("scale-risk-on-volatility");
        assertThat(goals.adaptations().get(1).name()).isEqualTo("tighten-trust-on-anomaly");

        // Verify update action fields were parsed correctly
        var updateRule = goals.adaptations().get(1);
        assertThat(updateRule.actions()).hasSize(1);
        var action = updateRule.actions().get(0);
        assertThat(action).isInstanceOf(io.casehub.ops.api.deployment.AdaptationActionSpec.UpdateActionSpec.class);
        var updateAction = (io.casehub.ops.api.deployment.AdaptationActionSpec.UpdateActionSpec) action;
        assertThat(updateAction.fields()).containsEntry("threshold", 0.9);
        assertThat(updateAction.fields()).containsEntry("bootstrapEscalationRequired", true);
    }

    @Test
    void baseCompilationProducesExpectedNodes() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");
        DesiredStateGraph base = compiler.compile(goals, graphFactory);

        // Base topology: triage-agent, risk-agent, trading/work channel, review trust policy
        assertThat(base.nodes()).hasSize(4);
        assertThat(base.nodes()).containsKeys(
            NodeId.of("triage-agent"),
            NodeId.of("risk-agent"),
            NodeId.of("trading/work"),
            NodeId.of("review"));
    }

    @Test
    void noSituationsActiveGraphUnchangedFromBase() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");
        situationSource.setSituations("tenant-1", List.of());

        manager.initialize("tenant-1", goals);

        assertThat(reconciliationTarget.startCalls).hasSize(1);
        var graph = reconciliationTarget.startCalls.get(0).graph;
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes()).containsKeys(
            NodeId.of("triage-agent"),
            NodeId.of("risk-agent"),
            NodeId.of("trading/work"),
            NodeId.of("review"));
    }

    @Test
    void volatilitySpikeScalesRiskAgent() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        // Volatility-spike at 0.85 with minConfidence 0.7
        // Effective = (0.85 - 0.7) / (1.0 - 0.7) = 0.15 / 0.3 = 0.5
        // Instances = 1 + (max - min) * effective = 1 + (5 - 1) * 0.5 = 1 + 2 = 3
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.85, Map.of(), Instant.now())));

        manager.initialize("tenant-1", goals);

        assertThat(reconciliationTarget.startCalls).hasSize(1);
        var graph = reconciliationTarget.startCalls.get(0).graph;

        // Should have: triage-agent, risk-agent (base), risk-agent~2, risk-agent~3,
        // trading/work, review = 6 nodes
        assertThat(graph.nodes()).hasSize(6);
        assertThat(graph.nodes()).containsKeys(
            NodeId.of("triage-agent"),
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3"),
            NodeId.of("trading/work"),
            NodeId.of("review"));
    }

    @Test
    void marketAnomalyUpdatesTrustThreshold() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("market-anomaly", 0.7, Map.of(), Instant.now())));

        manager.initialize("tenant-1", goals);

        assertThat(reconciliationTarget.startCalls).hasSize(1);
        var graph = reconciliationTarget.startCalls.get(0).graph;

        // Trust policy should be updated
        var trustNode = graph.nodes().get(NodeId.of("review"));
        assertThat(trustNode).isNotNull();
        var trustSpec = (TrustPolicyNodeSpec) trustNode.spec();
        assertThat(trustSpec.threshold()).isEqualTo(0.9);
        assertThat(trustSpec.bootstrapEscalationRequired()).isTrue();
    }

    @Test
    void bothSituationsActiveAppliesBothAdaptations() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.85, Map.of(), Instant.now()),
            new ActiveSituation("market-anomaly", 0.7, Map.of(), Instant.now())));

        manager.initialize("tenant-1", goals);

        assertThat(reconciliationTarget.startCalls).hasSize(1);
        var graph = reconciliationTarget.startCalls.get(0).graph;

        // Risk agent scaled (3 instances) + triage-agent + channel + trust = 6 nodes
        assertThat(graph.nodes()).hasSize(6);
        assertThat(graph.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3"));

        // Trust threshold updated
        var trustNode = graph.nodes().get(NodeId.of("review"));
        var trustSpec = (TrustPolicyNodeSpec) trustNode.spec();
        assertThat(trustSpec.threshold()).isEqualTo(0.9);
    }

    @Test
    void situationResolvesGraphReturnsToBase() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        // Start with situation active
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.85, Map.of(), Instant.now())));

        manager.initialize("tenant-1", goals);
        reconciliationTarget.clear();

        // Situation resolves
        situationSource.setSituations("tenant-1", List.of());

        manager.onSituationChange(new SituationChangeEvent("tenant-1"));

        assertThat(reconciliationTarget.updateDesiredCalls).hasSize(1);
        var graph = reconciliationTarget.updateDesiredCalls.get(0).graph;

        // Back to base: 4 nodes
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes()).containsKeys(
            NodeId.of("triage-agent"),
            NodeId.of("risk-agent"),
            NodeId.of("trading/work"),
            NodeId.of("review"));

        // No scaled replicas
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("risk-agent~2"));
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("risk-agent~3"));
    }

    @Test
    void situationConfidenceBelowDeactivateThresholdRemovesAdaptation() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        // Start with high confidence
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.9, Map.of(), Instant.now())));

        manager.initialize("tenant-1", goals);
        reconciliationTarget.clear();

        // Drop below deactivateBelow (0.5)
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.4, Map.of(), Instant.now())));

        manager.onSituationChange(new SituationChangeEvent("tenant-1"));

        assertThat(reconciliationTarget.updateDesiredCalls).hasSize(1);
        var graph = reconciliationTarget.updateDesiredCalls.get(0).graph;

        // Deactivated — back to base
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("risk-agent~2"));
    }

    @Test
    void confidenceDropCausesScaleDown() {
        DeploymentGoals goals = loader.load("test-deployment/adaptive-topology.yaml");

        // Start with high confidence (0.85)
        // Effective = (0.85 - 0.7) / (1.0 - 0.7) = 0.5 → instanceCount = 1 + (5-1)*0.5 = 3
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.85, Map.of(), Instant.now())));

        manager.initialize("tenant-1", goals);
        reconciliationTarget.clear();

        // Lower confidence to 0.76
        // Effective = (0.76 - 0.7) / (1.0 - 0.7) = 0.2 → instanceCount = 1 + (5-1)*0.2 = 1.8 → 1
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", 0.76, Map.of(), Instant.now())));

        manager.onSituationChange(new SituationChangeEvent("tenant-1"));

        assertThat(reconciliationTarget.updateDesiredCalls).hasSize(1);
        var graph = reconciliationTarget.updateDesiredCalls.get(0).graph;

        // Scaled down to 1 instance (base only)
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes()).containsKey(NodeId.of("risk-agent"));
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("risk-agent~2"));
    }

    // --- test doubles ---

    /**
     * Controllable SituationSource that returns pre-configured situations per tenant.
     */
    static class StubSituationSource implements SituationSource {

        private final ConcurrentHashMap<String, List<ActiveSituation>> situations =
            new ConcurrentHashMap<>();

        void setSituations(String tenancyId, List<ActiveSituation> active) {
            situations.put(tenancyId, List.copyOf(active));
        }

        @Override
        public List<ActiveSituation> activeSituations(String tenancyId) {
            return situations.getOrDefault(tenancyId, List.of());
        }
    }

    /**
     * Test spy that records ReconciliationTarget method calls.
     */
    static class SpyReconciliationTarget implements AdaptiveTopologyManager.ReconciliationTarget {

        final CopyOnWriteArrayList<StartCall> startCalls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<UpdateDesiredCall> updateDesiredCalls =
            new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> requestReconciliationCalls =
            new CopyOnWriteArrayList<>();

        @Override
        public void start(String tenancyId, DesiredStateGraph desired) {
            startCalls.add(new StartCall(tenancyId, desired));
        }

        @Override
        public void updateDesired(String tenancyId, DesiredStateGraph newDesired) {
            updateDesiredCalls.add(new UpdateDesiredCall(tenancyId, newDesired));
        }

        @Override
        public void requestReconciliation(String tenancyId) {
            requestReconciliationCalls.add(tenancyId);
        }

        void clear() {
            startCalls.clear();
            updateDesiredCalls.clear();
            requestReconciliationCalls.clear();
        }

        record StartCall(String tenancyId, DesiredStateGraph graph) {}
        record UpdateDesiredCall(String tenancyId, DesiredStateGraph graph) {}
    }
}
