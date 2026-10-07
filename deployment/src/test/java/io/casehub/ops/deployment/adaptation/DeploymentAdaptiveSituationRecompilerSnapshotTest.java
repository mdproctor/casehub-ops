package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.AdaptationActionSpec;
import io.casehub.ops.api.deployment.AdaptationRuleSpec;
import io.casehub.ops.api.deployment.AdaptationTrigger;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ops.api.deployment.GoalEntry;
import io.casehub.ops.deployment.DeploymentGoalCompiler;
import io.casehub.ras.api.ActiveSituation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentAdaptiveSituationRecompilerSnapshotTest {

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeploymentGoalCompiler compiler = new DeploymentGoalCompiler();

    private DeploymentAdaptiveSituationRecompiler recompiler;

    @BeforeEach
    void setUp() throws Exception {
        recompiler = new DeploymentAdaptiveSituationRecompiler();
        setField(recompiler, "compiler", compiler);
        setField(recompiler, "mapper", mapper);
        setField(recompiler, "meterRegistry", new SimpleMeterRegistry());
    }

    @Test
    void snapshotReturnsEmptyWhenNoTenantRegistered() {
        var snapshot = recompiler.getAdaptationSnapshot("unknown-tenant");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.activeRules()).isEmpty();
        assertThat(snapshot.trackedSituations()).isEmpty();
    }

    @Test
    void snapshotReturnsRegisteredRulesBeforeAnySituation() {
        registerTenantWithScaleRule("tenant-1");

        var snapshot = recompiler.getAdaptationSnapshot("tenant-1");

        assertThat(snapshot.activeRules()).hasSize(1);
        assertThat(snapshot.activeRules().get(0).ruleName()).isEqualTo("scale-risk");
        assertThat(snapshot.activeRules().get(0).situationId()).isEqualTo("volatility-spike");
        assertThat(snapshot.activeRules().get(0).active()).isFalse();
        assertThat(snapshot.trackedSituations()).isEmpty();
    }

    @Test
    void snapshotReflectsTrackedSituationAfterRecompile() {
        registerTenantWithScaleRule("tenant-1");

        var desired = compiler.compile(goalsWithScaleRule(), factory);
        var graph = ((io.casehub.desiredstate.api.CompilationResult.SingleGraph) desired).graph();
        var situation = new ActiveSituation("volatility-spike", "key1", "tenant-1",
                0.85, Map.of(), Instant.now(), Instant.now(), 1);

        recompiler.recompile("tenant-1", graph,
                new io.casehub.desiredstate.api.ActualState(Map.of()),
                situation, factory);

        var snapshot = recompiler.getAdaptationSnapshot("tenant-1");

        assertThat(snapshot.trackedSituations()).hasSize(1);
        assertThat(snapshot.trackedSituations().get(0).situationId()).isEqualTo("volatility-spike");
        assertThat(snapshot.trackedSituations().get(0).confidence()).isEqualTo(0.85);
        assertThat(snapshot.activeRules().get(0).active()).isTrue();
    }

    private void registerTenantWithScaleRule(String tenancyId) {
        recompiler.register(tenancyId, goalsWithScaleRule(), Map.of(), factory);
    }

    private DeploymentGoals goalsWithScaleRule() {
        var trigger = new AdaptationTrigger("volatility-spike", 0.7, 0.5, Duration.ofMinutes(5));
        var scaleAction = new AdaptationActionSpec.ScaleActionSpec("risk-agent", 1, 5);
        var ruleSpec = new AdaptationRuleSpec("scale-risk", trigger, List.of(scaleAction));
        return new DeploymentGoals(
                List.of(new GoalEntry<>(new AgentNodeSpec("risk-agent", "Risk Monitor",
                        "worker", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null), null)),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of(ruleSpec));
    }

    private static void setField(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
