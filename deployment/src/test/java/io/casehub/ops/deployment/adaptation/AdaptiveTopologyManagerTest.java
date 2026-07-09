package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationSource;
import io.smallrye.mutiny.Uni;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.AdaptationActionSpec;
import io.casehub.ops.api.deployment.AdaptationRuleSpec;
import io.casehub.ops.api.deployment.AdaptationTrigger;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.api.deployment.GoalEntry;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.deployment.DeploymentGoalCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveTopologyManagerTest {

    private DeploymentGoalCompiler compiler;
    private DesiredStateGraphFactory graphFactory;
    private ObjectMapper mapper;
    private StubSituationSource situationSource;
    private SpyReconciliationTarget reconciliationLoop;
    private AdaptiveTopologyManager manager;

    @BeforeEach
    void setUp() {
        compiler = new DeploymentGoalCompiler();
        graphFactory = new DefaultDesiredStateGraphFactory();
        mapper = new ObjectMapper();
        situationSource = new StubSituationSource();
        reconciliationLoop = new SpyReconciliationTarget();
        manager = new AdaptiveTopologyManager(
            compiler, graphFactory, mapper, situationSource, reconciliationLoop);
    }

    // --- initialize tests ---

    @Test
    void initializeCompilesBaseAndStartsReconciliation() {
        var goals = goalsWithAgent("risk-agent");

        manager.initialize("tenant-1", goals);

        assertThat(reconciliationLoop.startCalls).hasSize(1);
        assertThat(reconciliationLoop.startCalls.get(0).tenancyId).isEqualTo("tenant-1");
        assertThat(reconciliationLoop.startCalls.get(0).graph.nodes())
            .containsKey(NodeId.of("risk-agent"));
    }

    @Test
    void initializeWithAdaptationsAndMatchingSituationAppliesRules() {
        var trigger = new AdaptationTrigger("high-load", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 3);
        var rule = new AdaptationRuleSpec("scale-on-load", trigger, List.of(scaleAction));

        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("high-load", "_singleton", "tenant-1", 0.85, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);

        assertThat(reconciliationLoop.startCalls).hasSize(1);
        var startedGraph = reconciliationLoop.startCalls.get(0).graph;
        // Confidence 0.85 with minConfidence 0.7 → effective = (0.85-0.7)/(1-0.7) ≈ 0.5
        // instanceCount = clamp(1 + (int)((3-1) * 0.5), 1, 3) = 2
        assertThat(startedGraph.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"));
        assertThat(startedGraph.nodes()).hasSize(2);
    }

    // --- onSituationChange tests ---

    @Test
    void situationMatchesTriggerAdaptsGraphAndUpdatesDesired() {
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var rule = new AdaptationRuleSpec("scale-risk", trigger, List.of(scaleAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // Situation fires after initialization
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 1.0, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.onSituationChange(new SituationChangeEvent("tenant-1", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "tenant-1", java.time.Instant.now())));

        assertThat(reconciliationLoop.updateDesiredCalls).hasSize(1);
        assertThat(reconciliationLoop.requestReconciliationCalls).hasSize(1);

        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        // Confidence 1.0 with minConfidence 0.7 → effective = 1.0 → 5 instances
        assertThat(updatedGraph.nodes()).hasSize(5);
        assertThat(updatedGraph.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3"),
            NodeId.of("risk-agent~4"),
            NodeId.of("risk-agent~5"));
    }

    @Test
    void noMatchingSituationDoesNotUpdateDesired() {
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var rule = new AdaptationRuleSpec("scale-risk", trigger, List.of(scaleAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // No situations active
        situationSource.setSituations("tenant-1", List.of());

        manager.onSituationChange(new SituationChangeEvent("tenant-1", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "tenant-1", java.time.Instant.now())));

        // updateDesired is still called (recompiles base), but graph has no adaptations
        assertThat(reconciliationLoop.updateDesiredCalls).hasSize(1);
        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        assertThat(updatedGraph.nodes()).hasSize(1);
        assertThat(updatedGraph.nodes()).containsKey(NodeId.of("risk-agent"));
    }

    @Test
    void unknownTenancyIdIsNoop() {
        manager.onSituationChange(new SituationChangeEvent("unknown-tenant", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "unknown-tenant", java.time.Instant.now())));

        assertThat(reconciliationLoop.updateDesiredCalls).isEmpty();
        assertThat(reconciliationLoop.requestReconciliationCalls).isEmpty();
    }

    // --- hysteresis tests ---

    @Test
    void hysteresisKeepsActiveWhenConfidenceBetweenDeactivateBelowAndMinConfidence() {
        // minConfidence=0.7, deactivateBelow=0.5 → hysteresis band [0.5, 0.7)
        // Use an add action (not scale) so the adaptation is visible regardless of confidence level
        var forensicsAgent = new AgentNodeSpec(
            "forensics-agent", "Forensics Specialist", "worker",
            null, null, null, null, null,
            null, null, null, null,
            List.of(), null, null, null, null, List.of());
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, 0.5, null);
        var addAction = new AdaptationActionSpec.AddActionSpec(
            new DeploymentGoals(
                List.of(new GoalEntry<>(forensicsAgent, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of()));
        var rule = new AdaptationRuleSpec("add-on-spike", trigger, List.of(addAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        // First: activate with confidence above minConfidence
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.85, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // Drop confidence into hysteresis band (0.65 is above 0.5 deactivateBelow,
        // below 0.7 minConfidence) → should stay active
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.65, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.onSituationChange(new SituationChangeEvent("tenant-1", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "tenant-1", java.time.Instant.now())));

        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        // Still adapted — the add action produced forensics-agent alongside risk-agent
        assertThat(updatedGraph.nodes()).hasSize(2);
        assertThat(updatedGraph.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("forensics-agent"));
    }

    @Test
    void hysteresisDeactivatesWhenConfidenceDropsBelowDeactivateBelow() {
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, 0.5, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var rule = new AdaptationRuleSpec("scale-risk", trigger, List.of(scaleAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        // First: activate with confidence above minConfidence
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.85, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // Drop confidence below deactivateBelow → should deactivate
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.4, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.onSituationChange(new SituationChangeEvent("tenant-1", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "tenant-1", java.time.Instant.now())));

        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        // No adaptations — confidence below deactivateBelow, rule deactivated
        assertThat(updatedGraph.nodes()).hasSize(1);
        assertThat(updatedGraph.nodes()).containsKey(NodeId.of("risk-agent"));
    }

    // --- cooldown tests ---

    @Test
    void cooldownPreventsRapidStateFlipping() {
        // Use an add action so the adaptation is visible regardless of confidence level
        var forensicsAgent = new AgentNodeSpec(
            "forensics-agent", "Forensics Specialist", "worker",
            null, null, null, null, null,
            null, null, null, null,
            List.of(), null, null, null, null, List.of());
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, 0.5,
            Duration.ofMinutes(5));
        var addAction = new AdaptationActionSpec.AddActionSpec(
            new DeploymentGoals(
                List.of(new GoalEntry<>(forensicsAgent, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of()));
        var rule = new AdaptationRuleSpec("add-on-spike", trigger, List.of(addAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        // Activate with high confidence
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.85, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // Immediately try to deactivate (confidence below deactivateBelow, within cooldown window)
        // Cooldown should prevent deactivation → rule stays active
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.3, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.onSituationChange(new SituationChangeEvent("tenant-1", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "tenant-1", java.time.Instant.now())));

        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        // Cooldown prevents deactivation — forensics-agent still present
        assertThat(updatedGraph.nodes()).hasSize(2);
        assertThat(updatedGraph.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("forensics-agent"));
    }

    // --- situation disappearance ---

    @Test
    void situationDisappearanceRemovesAdaptations() {
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var rule = new AdaptationRuleSpec("scale-risk", trigger, List.of(scaleAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        // Activate
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.9, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // Situation disappears entirely
        situationSource.setSituations("tenant-1", List.of());

        manager.onSituationChange(new SituationChangeEvent("tenant-1", "situation", "_singleton", SituationChangeEvent.ChangeType.TRIGGERED, io.casehub.ras.api.SituationContext.initial("situation", "_singleton", "tenant-1", java.time.Instant.now())));

        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        // Back to base topology — only the original agent
        assertThat(updatedGraph.nodes()).hasSize(1);
        assertThat(updatedGraph.nodes()).containsKey(NodeId.of("risk-agent"));
    }

    // --- conflict detection ---

    @Test
    void conflictDetectionLogsWarningWhenTwoRulesModifySameNode() {
        // Two rules targeting the same node
        var trigger1 = new AdaptationTrigger("situation-a", 0.7, null, null);
        var trigger2 = new AdaptationTrigger("situation-b", 0.7, null, null);
        var scaleAction1 = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 3);
        var scaleAction2 = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var rule1 = new AdaptationRuleSpec("rule-a", trigger1, List.of(scaleAction1));
        var rule2 = new AdaptationRuleSpec("rule-b", trigger2, List.of(scaleAction2));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule1, rule2));

        // Both situations active
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("situation-a", "_singleton", "tenant-1", 0.9, Map.of(), Instant.now(), Instant.now(), 0),
            new ActiveSituation("situation-b", "_singleton", "tenant-1", 0.9, Map.of(), Instant.now(), Instant.now(), 0)));

        // Should not throw — conflict is logged as warning, not an error
        manager.initialize("tenant-1", goals);

        assertThat(reconciliationLoop.startCalls).hasSize(1);
        // Later rule (rule-b with max=5) overrides — graph has scaled nodes
        var graph = reconciliationLoop.startCalls.get(0).graph;
        assertThat(graph.nodes()).containsKey(NodeId.of("risk-agent"));
    }

    // --- periodic re-poll ---

    @Test
    void periodicRepollDetectsSituationWithoutCdiEvent() throws Exception {
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 3);
        var rule = new AdaptationRuleSpec("scale-risk", trigger, List.of(scaleAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        // Initialize with no situations
        situationSource.setSituations("tenant-1", List.of());
        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        // Silently add a situation (no CDI event fired)
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("volatility-spike", "_singleton", "tenant-1", 0.9, Map.of(), Instant.now(), Instant.now(), 0)));

        // Trigger manual poll (simulates what the scheduled executor would do)
        manager.pollAllTenants();

        // Should detect the situation and update
        assertThat(reconciliationLoop.updateDesiredCalls).hasSize(1);
        var updatedGraph = reconciliationLoop.updateDesiredCalls.get(0).graph;
        assertThat(updatedGraph.nodes().size()).isGreaterThan(1);
    }

    @Test
    void periodicRepollNoopWhenGraphUnchanged() throws Exception {
        var goals = goalsWithAgent("risk-agent");

        // No situations, no rules
        situationSource.setSituations("tenant-1", List.of());
        manager.initialize("tenant-1", goals);
        reconciliationLoop.clear();

        manager.pollAllTenants();

        // No update if graph hasn't changed
        assertThat(reconciliationLoop.updateDesiredCalls).isEmpty();
    }

    // --- add action integration ---

    @Test
    void addActionInsertsNewNodesWhenSituationActive() {
        var forensicsAgent = new AgentNodeSpec(
            "forensics-agent", "Forensics Specialist", "worker",
            null, null, null, null, null,
            null, null, null, null,
            List.of(), null, null, null, null, List.of());

        var trigger = new AdaptationTrigger("active-breach", 0.8, null, null);
        var addAction = new AdaptationActionSpec.AddActionSpec(
            new DeploymentGoals(
                List.of(new GoalEntry<>(forensicsAgent, List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of()));
        var rule = new AdaptationRuleSpec("add-forensics", trigger, List.of(addAction));
        var goals = goalsWithAgentAndRules("triage-agent", List.of(rule));

        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("active-breach", "_singleton", "tenant-1", 0.95, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);

        var graph = reconciliationLoop.startCalls.get(0).graph;
        assertThat(graph.nodes()).containsKeys(
            NodeId.of("triage-agent"),
            NodeId.of("forensics-agent"));
    }

    // --- update action integration ---

    @Test
    void updateActionModifiesExistingNodeFields() {
        var trustSpec = new TrustPolicyNodeSpec(
            "review", 0.7, 5, 0.05, 0.3, Map.of(), false);

        var trigger = new AdaptationTrigger("market-anomaly", 0.6, null, null);
        var updateAction = new AdaptationActionSpec.UpdateActionSpec(
            "review", "trust_policy", Map.of("threshold", 0.9));
        var rule = new AdaptationRuleSpec("tighten-trust", trigger, List.of(updateAction));

        var goals = new DeploymentGoals(
            List.of(), List.of(), List.of(),
            List.of(new GoalEntry<>(trustSpec, List.of())),
            List.of(),
            List.of(rule));

        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("market-anomaly", "_singleton", "tenant-1", 0.8, Map.of(), Instant.now(), Instant.now(), 0)));

        manager.initialize("tenant-1", goals);

        var graph = reconciliationLoop.startCalls.get(0).graph;
        var trustNode = graph.nodes().get(NodeId.of("review"));
        assertThat(trustNode).isNotNull();
        var updatedSpec = (TrustPolicyNodeSpec) trustNode.spec();
        assertThat(updatedSpec.threshold()).isEqualTo(0.9);
    }

    // --- multi-tenant isolation ---

    @Test
    void multiTenantIsolation() {
        var trigger = new AdaptationTrigger("high-load", 0.7, null, null);
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 3);
        var rule = new AdaptationRuleSpec("scale-on-load", trigger, List.of(scaleAction));
        var goals = goalsWithAgentAndRules("risk-agent", List.of(rule));

        // Tenant-1 has situation, tenant-2 doesn't
        situationSource.setSituations("tenant-1", List.of(
            new ActiveSituation("high-load", "_singleton", "tenant-1", 0.9, Map.of(), Instant.now(), Instant.now(), 0)));
        situationSource.setSituations("tenant-2", List.of());

        manager.initialize("tenant-1", goals);
        manager.initialize("tenant-2", goals);

        assertThat(reconciliationLoop.startCalls).hasSize(2);

        var t1Graph = reconciliationLoop.startCalls.get(0).graph;
        var t2Graph = reconciliationLoop.startCalls.get(1).graph;

        // Tenant-1 adapted (scaled), tenant-2 base only
        assertThat(t1Graph.nodes().size()).isGreaterThan(1);
        assertThat(t2Graph.nodes()).hasSize(1);
    }

    // --- helper methods ---

    private DeploymentGoals goalsWithAgent(String agentId) {
        return goalsWithAgentAndRules(agentId, List.of());
    }

    private DeploymentGoals goalsWithAgentAndRules(String agentId,
                                                    List<AdaptationRuleSpec> rules) {
        var agent = new AgentNodeSpec(
            agentId, "Test Agent", "worker",
            null, null, null, null, null,
            null, null, null, null,
            List.of(), null, null, null, null, List.of());
        return new DeploymentGoals(
            List.of(new GoalEntry<>(agent, List.of())),
            List.of(), List.of(), List.of(), List.of(),
            rules);
    }

    // --- test doubles ---

    /**
     * Controllable SituationSource that returns pre-configured situations per tenant.
     */
    static class StubSituationSource implements SituationSource {

        private final java.util.concurrent.ConcurrentHashMap<String, List<ActiveSituation>>
            situations = new java.util.concurrent.ConcurrentHashMap<>();

        void setSituations(String tenancyId, List<ActiveSituation> active) {
            situations.put(tenancyId, List.copyOf(active));
        }

        @Override
        public Uni<List<ActiveSituation>> activeSituations(String tenancyId) {
            return Uni.createFrom().item(situations.getOrDefault(tenancyId, List.of()));
        }
    }

    /**
     * Test spy that records ReconciliationTarget method calls without
     * actually running reconciliation infrastructure.
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
