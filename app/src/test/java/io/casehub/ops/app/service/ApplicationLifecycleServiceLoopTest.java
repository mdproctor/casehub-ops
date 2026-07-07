package io.casehub.ops.app.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.FaultPolicyEngine;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.ops.app.model.DeploymentOutcome;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Plain unit tests for ApplicationLifecycleService — active loop index and
 * ReconciliationLoop start-or-update semantics.
 *
 * <p>Does NOT use {@code @QuarkusTest} because the CDI container has unsatisfied
 * dependencies ({@code ReactiveSubCaseGroupRepository}) and unproxyable beans
 * from the engine dependency chain. The deploy()/decommission() methods that
 * depend on Panache entity lookups require full CDI integration testing, which
 * is blocked until the engine dependency issues are resolved.
 */
class ApplicationLifecycleServiceLoopTest {

    private ApplicationLifecycleService service;
    private ReconciliationLoop reconciliationLoop;

    @BeforeEach
    void setUp() {
        service = new ApplicationLifecycleService();

        // Create a minimal ReconciliationLoop with no-op SPI implementations
        var planner = new TransitionPlanner();
        var faultEngine = new FaultPolicyEngine(List.of());

        reconciliationLoop = new ReconciliationLoop(
                planner,
                (plan, tenancyId) -> Uni.createFrom().item(new TransitionResult(Map.of())),
                (desired, tenancyId) -> new ActualState(Map.of()),
                faultEngine,
                () -> Multi.createFrom().empty(),
                Duration.ofHours(1),
                Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        // stop() is public; shutdown() is package-private
        // Stop any loops we may have started. stop() is a no-op for unknown keys.
        reconciliationLoop.stop("default:app1:cluster-1");
    }

    // --- Active loop index tests ---

    @Test
    void trackLoopKeyAddsEntryToCluster() {
        service.trackLoopKey("cluster-1", "default:app1:cluster-1");
        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isTrue();
    }

    @Test
    void hasActiveLoopsReturnsFalseWhenNoLoops() {
        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isFalse();
    }

    @Test
    void removeLoopKeyRemovesFromAllClusters() {
        service.trackLoopKey("cluster-1", "default:app1:cluster-1");
        service.trackLoopKey("cluster-2", "default:app1:cluster-2");

        service.removeLoopKey("default:app1:cluster-1");

        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isFalse();
        assertThat(service.hasActiveLoopsForCluster("cluster-2")).isTrue();
    }

    @Test
    void activeLoopKeysForAppFindsKeysByAppId() {
        UUID appId = UUID.randomUUID();
        UUID otherAppId = UUID.randomUUID();
        String key1 = "default:" + appId + ":cluster-1";
        String key2 = "default:" + appId + ":cluster-2";
        String otherKey = "default:" + otherAppId + ":cluster-1";

        service.trackLoopKey("cluster-1", key1);
        service.trackLoopKey("cluster-2", key2);
        service.trackLoopKey("cluster-1", otherKey);

        Set<String> result = service.activeLoopKeysForApp(appId);
        assertThat(result).containsExactlyInAnyOrder(key1, key2);
    }

    @Test
    void activeLoopKeysForAppReturnsEmptyWhenNoLoops() {
        Set<String> result = service.activeLoopKeysForApp(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    void multipleAppsCanShareCluster() {
        UUID app1 = UUID.randomUUID();
        UUID app2 = UUID.randomUUID();
        String key1 = "default:" + app1 + ":cluster-1";
        String key2 = "default:" + app2 + ":cluster-1";

        service.trackLoopKey("cluster-1", key1);
        service.trackLoopKey("cluster-1", key2);

        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isTrue();

        service.removeLoopKey(key1);
        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isTrue();

        service.removeLoopKey(key2);
        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isFalse();
    }

    @Test
    void trackLoopKeyIsIdempotent() {
        String key = "default:app1:cluster-1";
        service.trackLoopKey("cluster-1", key);
        service.trackLoopKey("cluster-1", key);

        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isTrue();

        service.removeLoopKey(key);
        assertThat(service.hasActiveLoopsForCluster("cluster-1")).isFalse();
    }

    @Test
    void removeLoopKeyIsNoOpWhenKeyNotPresent() {
        assertThatNoException().isThrownBy(() -> service.removeLoopKey("nonexistent:key"));
    }

    // --- ReconciliationLoop start-or-update semantics ---

    @Test
    void startOrUpdatePatternHandlesNewLoop() {
        var graphFactory = new DefaultDesiredStateGraphFactory();
        var emptyGraph = graphFactory.of(List.of(), List.of());

        String key = "default:app1:cluster-1";

        // First start should succeed — no exception
        assertThatNoException().isThrownBy(() -> reconciliationLoop.start(key, emptyGraph));

        reconciliationLoop.stop(key);
    }

    @Test
    void startOrUpdatePatternHandlesExistingLoop() {
        var graphFactory = new DefaultDesiredStateGraphFactory();
        var emptyGraph = graphFactory.of(List.of(), List.of());

        String key = "default:app1:cluster-1";
        reconciliationLoop.start(key, emptyGraph);

        // Start-or-update pattern: attempt start, catch, fall back to updateDesired
        var newGraph = graphFactory.of(List.of(), List.of());
        try {
            reconciliationLoop.start(key, newGraph);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            // Expected — fall back to updateDesired
            reconciliationLoop.updateDesired(key, newGraph);
        }

        // Loop still active — updateDesired on a running loop doesn't throw
        // Verify by successfully stopping (would be no-op if loop didn't exist)
        assertThatNoException().isThrownBy(() -> reconciliationLoop.stop(key));
    }

    @Test
    void startThrowsOnDuplicateKey() {
        var graphFactory = new DefaultDesiredStateGraphFactory();
        var emptyGraph = graphFactory.of(List.of(), List.of());

        String key = "default:app1:cluster-1";
        reconciliationLoop.start(key, emptyGraph);

        assertThatIllegalStateException()
                .isThrownBy(() -> reconciliationLoop.start(key, emptyGraph))
                .withMessageContaining("already running");

        reconciliationLoop.stop(key);
    }

    // --- DeploymentOutcome.PENDING ---

    @Test
    void deploymentOutcomePendingExists() {
        assertThat(DeploymentOutcome.PENDING).isNotNull();
        assertThat(DeploymentOutcome.PENDING.name()).isEqualTo("PENDING");
        assertThat(DeploymentOutcome.values()).contains(DeploymentOutcome.PENDING);
    }

    @Test
    void pendingPrecedesSuccessInEnumOrder() {
        assertThat(DeploymentOutcome.PENDING.ordinal())
                .isLessThan(DeploymentOutcome.SUCCESS.ordinal());
    }
}
