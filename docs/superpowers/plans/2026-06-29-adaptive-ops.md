# Adaptive Ops Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable desired-state topology adaptation driven by RAS situations — self-healing, auto-scaling, and adaptive security posture for casehub-fsitrading and casehub-soc.

**Architecture:** `SituationSource` SPI in casehub-desiredstate-api defines the signal contract. `AdaptiveTopologyManager` in casehub-ops-deployment queries active situations and re-compiles the `DesiredStateGraph` with adaptation rules from `casehub-deployment.yaml`. `ReconciliationLoop.requestReconciliation()` triggers immediate reconciliation on situation changes. FaultPolicy stays no-op — self-healing is handled by the reconciliation loop.

**Tech Stack:** Java 21, Quarkus 3.32.2, Jackson (YAML + tree model), Smallrye Mutiny, CDI async events

## Global Constraints

- Java 21. Build: `mvn --batch-mode install`
- casehub-desiredstate-api is Tier 1 (pure Java, no Quarkus, no JPA). Jandex indexed.
- casehub-ops-api is Tier 1. casehub-ops-deployment is Tier 3 (CDI, runtime wiring).
- `@JsonIgnoreProperties(ignoreUnknown = true)` on all Jackson-deserialized records.
- `tenancyId` must be explicit parameter on all tenant-scoped operations and CDI event records.
- SPI signature changes require all implementations updated in the same commit.
- New SPI default methods require contract tests.
- `@DefaultBean` for no-op SPI implementations in runtime; `@ApplicationScoped` for real implementations.
- Single-domain-per-classpath constraint — no CDI qualifiers on domain SPIs.
- All commits reference an issue: desiredstate#49 for desiredstate changes, ops#25 for ops changes.

---

### Task 1: SituationSource SPI + ActiveSituation in casehub-desiredstate-api

**Repo:** casehub-desiredstate
**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/SituationSource.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ActiveSituation.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/SituationChangeEvent.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/ActiveSituationTest.java`

**Interfaces:**
- Consumes: nothing — this is the foundation
- Produces: `SituationSource.activeSituations(String tenancyId)` returns `List<ActiveSituation>`. `ActiveSituation(String situationId, double confidence, Map<String, Object> evidence, Instant since)`. `SituationChangeEvent(String tenancyId)`.

- [ ] **Step 1: Write ActiveSituation validation test**

```java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ActiveSituationTest {

    @Test
    void rejectsNullSituationId() {
        assertThrows(NullPointerException.class, () ->
            new ActiveSituation(null, 0.5, Map.of(), Instant.now()));
    }

    @Test
    void rejectsConfidenceBelowZero() {
        assertThrows(IllegalArgumentException.class, () ->
            new ActiveSituation("test", -0.1, Map.of(), Instant.now()));
    }

    @Test
    void rejectsConfidenceAboveOne() {
        assertThrows(IllegalArgumentException.class, () ->
            new ActiveSituation("test", 1.1, Map.of(), Instant.now()));
    }

    @Test
    void rejectsNaN() {
        assertThrows(IllegalArgumentException.class, () ->
            new ActiveSituation("test", Double.NaN, Map.of(), Instant.now()));
    }

    @Test
    void acceptsValidBoundaries() {
        assertDoesNotThrow(() -> new ActiveSituation("test", 0.0, Map.of(), Instant.now()));
        assertDoesNotThrow(() -> new ActiveSituation("test", 1.0, Map.of(), Instant.now()));
    }

    @Test
    void nullEvidenceDefaultsToEmptyMap() {
        var s = new ActiveSituation("test", 0.5, null, Instant.now());
        assertEquals(Map.of(), s.evidence());
    }

    @Test
    void evidenceIsDefensivelyCopied() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("key", "val");
        var s = new ActiveSituation("test", 0.5, mutable, Instant.now());
        assertThrows(UnsupportedOperationException.class, () -> s.evidence().put("x", "y"));
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn -C /Users/mdproctor/claude/casehub/desiredstate -pl api -Dtest=ActiveSituationTest test --batch-mode`
Expected: compilation failure — `ActiveSituation` does not exist

- [ ] **Step 3: Implement ActiveSituation, SituationSource, SituationChangeEvent**

`api/src/main/java/io/casehub/desiredstate/api/ActiveSituation.java`:
```java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ActiveSituation(
        String situationId,
        double confidence,
        Map<String, Object> evidence,
        Instant since
) {
    public ActiveSituation {
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(since, "since");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be 0.0–1.0, got: " + confidence);
        }
        evidence = evidence != null ? Map.copyOf(evidence) : Map.of();
    }
}
```

`api/src/main/java/io/casehub/desiredstate/api/SituationSource.java`:
```java
package io.casehub.desiredstate.api;

import java.util.List;

public interface SituationSource {
    List<ActiveSituation> activeSituations(String tenancyId);
}
```

`api/src/main/java/io/casehub/desiredstate/api/SituationChangeEvent.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record SituationChangeEvent(String tenancyId) {
    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

Run: `mvn -C /Users/mdproctor/claude/casehub/desiredstate -pl api -Dtest=ActiveSituationTest test --batch-mode`
Expected: all 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add api/src/main/java/io/casehub/desiredstate/api/SituationSource.java api/src/main/java/io/casehub/desiredstate/api/ActiveSituation.java api/src/main/java/io/casehub/desiredstate/api/SituationChangeEvent.java api/src/test/java/io/casehub/desiredstate/api/ActiveSituationTest.java
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#49): SituationSource SPI + ActiveSituation + SituationChangeEvent in desiredstate API"
```

---

### Task 2: DefaultSituationSource @DefaultBean + requestReconciliation() in casehub-desiredstate runtime

**Repo:** casehub-desiredstate
**Files:**
- Create: `runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultSituationSource.java`
- Modify: `runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java` — add `requestReconciliation(String tenancyId)` method
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultSituationSourceTest.java`
- Test: `runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopRequestReconciliationTest.java`

**Interfaces:**
- Consumes: `SituationSource`, `SituationChangeEvent`, `ActiveSituation` from Task 1
- Produces: `DefaultSituationSource` (`@DefaultBean`, returns `List.of()`). `ReconciliationLoop.requestReconciliation(String tenancyId)` — schedules debounced reconciliation for a tenant.

- [ ] **Step 1: Write DefaultSituationSource test**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.SituationSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefaultSituationSourceTest {

    @Test
    void returnsEmptyList() {
        SituationSource source = new DefaultSituationSource();
        assertTrue(source.activeSituations("tenant-1").isEmpty());
    }

    @Test
    void acceptsAnyTenantId() {
        SituationSource source = new DefaultSituationSource();
        assertDoesNotThrow(() -> source.activeSituations("any-tenant"));
    }
}
```

- [ ] **Step 2: Write requestReconciliation test**

```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationLoopRequestReconciliationTest {

    private ReconciliationLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    @Test
    void requestReconciliationTriggersReconcileForTenant() throws Exception {
        var readActualCount = new AtomicInteger(0);
        var latch = new CountDownLatch(1);

        ActualStateAdapter adapter = (desired, tenancyId) -> {
            readActualCount.incrementAndGet();
            latch.countDown();
            return ActualState.empty();
        };

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new NoOpTransitionExecutor(),
            adapter,
            new FaultPolicyEngine(List.of()),
            EventSource.empty(),
            java.time.Duration.ofMillis(50),
            java.time.Duration.ofHours(1)  // long resync so only request triggers
        );

        var factory = new ImmutableDesiredStateGraph.Factory();
        loop.start("tenant-1", factory.empty());

        // Wait for initial reconciliation to settle
        Thread.sleep(200);
        int baseline = readActualCount.get();

        loop.requestReconciliation("tenant-1");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(readActualCount.get() > baseline);
    }

    @Test
    void requestReconciliationForUnknownTenantIsNoOp() {
        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new NoOpTransitionExecutor(),
            (desired, tenancyId) -> ActualState.empty(),
            new FaultPolicyEngine(List.of()),
            EventSource.empty()
        );
        assertDoesNotThrow(() -> loop.requestReconciliation("nonexistent"));
    }
}
```

Note: `NoOpTransitionExecutor` and `EventSource.empty()` may need to be created or may exist in the testing module. Adjust imports based on what exists. The test verifies `requestReconciliation` triggers `readActual` (proving a reconciliation cycle ran).

- [ ] **Step 3: Run tests — verify they fail**

Run: `mvn -C /Users/mdproctor/claude/casehub/desiredstate -pl runtime -am -Dtest="DefaultSituationSourceTest,ReconciliationLoopRequestReconciliationTest" test --batch-mode -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `DefaultSituationSource` and `requestReconciliation()` don't exist

- [ ] **Step 4: Implement DefaultSituationSource**

`runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultSituationSource.java`:
```java
package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActiveSituation;
import io.casehub.desiredstate.api.SituationSource;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class DefaultSituationSource implements SituationSource {
    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return List.of();
    }
}
```

- [ ] **Step 5: Add requestReconciliation() to ReconciliationLoop**

In `ReconciliationLoop.java`, add the public method:
```java
public void requestReconciliation(String tenancyId) {
    TenantLoop tenant = loops.get(tenancyId);
    if (tenant != null) {
        tenant.scheduleReconciliation();
    }
}
```

In the inner `TenantLoop` class, add `scheduleReconciliation()`:
```java
private ScheduledFuture<?> requestedReconciliation;

void scheduleReconciliation() {
    synchronized (this) {
        if (requestedReconciliation != null && !requestedReconciliation.isDone()) {
            requestedReconciliation.cancel(false);
        }
        requestedReconciliation = scheduler.schedule(
            this::reconcile,
            debounceWindow.toMillis(),
            java.util.concurrent.TimeUnit.MILLISECONDS
        );
    }
}
```

Also update `stop()` to cancel the requested reconciliation future:
```java
void stop() {
    if (eventSubscription != null) eventSubscription.cancel();
    if (resyncFuture != null) resyncFuture.cancel(true);
    if (requestedReconciliation != null) requestedReconciliation.cancel(false);
}
```

- [ ] **Step 6: Run tests — verify they pass**

Run: `mvn -C /Users/mdproctor/claude/casehub/desiredstate -pl runtime -am -Dtest="DefaultSituationSourceTest,ReconciliationLoopRequestReconciliationTest" test --batch-mode -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all tests PASS

- [ ] **Step 7: Run full desiredstate test suite**

Run: `mvn -C /Users/mdproctor/claude/casehub/desiredstate --batch-mode install`
Expected: BUILD SUCCESS — no regressions

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/desiredstate add runtime/src/main/java/io/casehub/desiredstate/runtime/DefaultSituationSource.java runtime/src/main/java/io/casehub/desiredstate/runtime/ReconciliationLoop.java runtime/src/test/java/io/casehub/desiredstate/runtime/DefaultSituationSourceTest.java runtime/src/test/java/io/casehub/desiredstate/runtime/ReconciliationLoopRequestReconciliationTest.java
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#49): DefaultSituationSource @DefaultBean + requestReconciliation() on ReconciliationLoop"
```

---

### Task 3: AdaptationRuleSpec YAML types + AgentNodeSpec.withAgentId() in casehub-ops-api

**Repo:** casehub-ops
**Files:**
- Create: `api/src/main/java/io/casehub/ops/api/deployment/AdaptationRuleSpec.java`
- Create: `api/src/main/java/io/casehub/ops/api/deployment/AdaptationTrigger.java`
- Create: `api/src/main/java/io/casehub/ops/api/deployment/AdaptationActionSpec.java`
- Modify: `api/src/main/java/io/casehub/ops/api/deployment/DeploymentGoals.java` — add `adaptations` field
- Modify: `api/src/main/java/io/casehub/ops/api/deployment/AgentNodeSpec.java` — add `withAgentId(String)` copy method
- Test: `api/src/test/java/io/casehub/ops/api/deployment/AdaptationRuleSpecTest.java`
- Test: `api/src/test/java/io/casehub/ops/api/deployment/AgentNodeSpecWithAgentIdTest.java`

**Interfaces:**
- Consumes: existing `DeploymentGoals`, `AgentNodeSpec`, `DeploymentNodeSpec`, `GoalEntry`
- Produces: `AdaptationRuleSpec(String name, AdaptationTrigger trigger, List<AdaptationActionSpec> actions)`. `AdaptationTrigger(String situation, double minConfidence, Double deactivateBelow, Duration cooldown)`. `AdaptationActionSpec` — sealed with `ScaleActionSpec`, `AddActionSpec`, `UpdateActionSpec` variants. `DeploymentGoals.adaptations()` returns `List<AdaptationRuleSpec>`. `AgentNodeSpec.withAgentId(String newId)` returns a new `AgentNodeSpec` with only agentId changed.

- [ ] **Step 1: Write AgentNodeSpec.withAgentId test**

```java
package io.casehub.ops.api.deployment;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentNodeSpecWithAgentIdTest {

    @Test
    void withAgentIdChangesOnlyAgentId() {
        var original = new AgentNodeSpec(
            "original-id", "Test Agent", "worker", "claude",
            null, null, null, null, null, null, null,
            Map.of(), List.of(), null, null, null, null, List.of());
        var derived = original.withAgentId("derived-id");

        assertEquals("derived-id", derived.agentId());
        assertEquals("derived-id", derived.nodeId());
        assertEquals("Test Agent", derived.name());
        assertEquals("worker", derived.slot());
        assertEquals("claude", derived.provider());
    }

    @Test
    void withAgentIdRejectsNull() {
        var original = new AgentNodeSpec(
            "id", "name", "worker", null,
            null, null, null, null, null, null, null,
            Map.of(), List.of(), null, null, null, null, List.of());
        assertThrows(NullPointerException.class, () -> original.withAgentId(null));
    }
}
```

- [ ] **Step 2: Write AdaptationRuleSpec validation test**

```java
package io.casehub.ops.api.deployment;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AdaptationRuleSpecTest {

    @Test
    void rejectsNullName() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        var action = new AdaptationActionSpec.ScaleActionSpec("target", 1, 5);
        assertThrows(NullPointerException.class, () ->
            new AdaptationRuleSpec(null, trigger, List.of(action)));
    }

    @Test
    void rejectsEmptyActions() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationRuleSpec("rule", trigger, List.of()));
    }

    @Test
    void triggerDeactivateBelowDefaultsToMinConfidence() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        assertEquals(0.7, trigger.effectiveDeactivateBelow());
    }

    @Test
    void triggerDeactivateBelowUsesExplicitValue() {
        var trigger = new AdaptationTrigger("sit", 0.7, 0.5, null);
        assertEquals(0.5, trigger.effectiveDeactivateBelow());
    }

    @Test
    void triggerRejectsMinConfidenceOfOne() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationTrigger("sit", 1.0, null, null));
    }

    @Test
    void triggerCooldownDefaultsToZero() {
        var trigger = new AdaptationTrigger("sit", 0.7, null, null);
        assertEquals(Duration.ZERO, trigger.effectiveCooldown());
    }

    @Test
    void scaleActionRejectsMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.ScaleActionSpec("target", 5, 2));
    }

    @Test
    void scaleActionRejectsMinLessThanOne() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.ScaleActionSpec("target", 0, 5));
    }

    @Test
    void scaleActionRejectsTildeInTarget() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdaptationActionSpec.ScaleActionSpec("risk~agent", 1, 5));
    }
}
```

- [ ] **Step 3: Run tests — verify they fail**

Run: `mvn --batch-mode -pl api -Dtest="AgentNodeSpecWithAgentIdTest,AdaptationRuleSpecTest" test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure

- [ ] **Step 4: Implement AdaptationTrigger**

`api/src/main/java/io/casehub/ops/api/deployment/AdaptationTrigger.java`:
```java
package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdaptationTrigger(
        String situation,
        double minConfidence,
        Double deactivateBelow,
        Duration cooldown
) {
    public AdaptationTrigger {
        Objects.requireNonNull(situation, "situation");
        if (Double.isNaN(minConfidence) || minConfidence < 0.0 || minConfidence >= 1.0) {
            throw new IllegalArgumentException(
                "minConfidence must be [0.0, 1.0), got: " + minConfidence);
        }
        if (deactivateBelow != null && (deactivateBelow < 0.0 || deactivateBelow > minConfidence)) {
            throw new IllegalArgumentException(
                "deactivateBelow must be [0.0, minConfidence], got: " + deactivateBelow);
        }
    }

    public double effectiveDeactivateBelow() {
        return deactivateBelow != null ? deactivateBelow : minConfidence;
    }

    public Duration effectiveCooldown() {
        return cooldown != null ? cooldown : Duration.ZERO;
    }
}
```

- [ ] **Step 5: Implement AdaptationActionSpec sealed hierarchy**

`api/src/main/java/io/casehub/ops/api/deployment/AdaptationActionSpec.java`:
```java
package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AdaptationActionSpec.ScaleActionSpec.class, name = "scale"),
    @JsonSubTypes.Type(value = AdaptationActionSpec.AddActionSpec.class, name = "add"),
    @JsonSubTypes.Type(value = AdaptationActionSpec.UpdateActionSpec.class, name = "update")
})
public sealed interface AdaptationActionSpec {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScaleActionSpec(String target, int min, int max) implements AdaptationActionSpec {
        public ScaleActionSpec {
            Objects.requireNonNull(target, "target");
            if (target.contains("~")) {
                throw new IllegalArgumentException(
                    "scale target must not contain '~': " + target);
            }
            if (min < 1) {
                throw new IllegalArgumentException("min must be >= 1, got: " + min);
            }
            if (max < min) {
                throw new IllegalArgumentException(
                    "max must be >= min, got min=" + min + " max=" + max);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddActionSpec(DeploymentGoals nodes) implements AdaptationActionSpec {
        public AddActionSpec {
            Objects.requireNonNull(nodes, "nodes");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpdateActionSpec(
            String target,
            String nodeType,
            Map<String, Object> fields
    ) implements AdaptationActionSpec {
        public UpdateActionSpec {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(fields, "fields");
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("fields must not be empty");
            }
            fields = Map.copyOf(fields);
        }
    }
}
```

- [ ] **Step 6: Implement AdaptationRuleSpec**

`api/src/main/java/io/casehub/ops/api/deployment/AdaptationRuleSpec.java`:
```java
package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdaptationRuleSpec(
        String name,
        AdaptationTrigger trigger,
        List<AdaptationActionSpec> actions
) {
    public AdaptationRuleSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        actions = List.copyOf(actions);
    }
}
```

- [ ] **Step 7: Add adaptations field to DeploymentGoals**

Modify `DeploymentGoals.java`:
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentGoals(
        List<GoalEntry<AgentNodeSpec>> agents,
        List<GoalEntry<ChannelNodeSpec>> channels,
        List<GoalEntry<CaseTypeNodeSpec>> caseTypes,
        List<GoalEntry<TrustPolicyNodeSpec>> trust,
        List<GoalEntry<EndpointNodeSpec>> endpoints,
        List<AdaptationRuleSpec> adaptations
) {
    public DeploymentGoals {
        agents = agents != null ? List.copyOf(agents) : List.of();
        channels = channels != null ? List.copyOf(channels) : List.of();
        caseTypes = caseTypes != null ? List.copyOf(caseTypes) : List.of();
        trust = trust != null ? List.copyOf(trust) : List.of();
        endpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
        adaptations = adaptations != null ? List.copyOf(adaptations) : List.of();
    }
}
```

- [ ] **Step 8: Add withAgentId to AgentNodeSpec**

Add to `AgentNodeSpec.java`:
```java
public AgentNodeSpec withAgentId(String newId) {
    Objects.requireNonNull(newId, "newId");
    return new AgentNodeSpec(
        newId, name, slot, provider, modelFamily, modelVersion, version,
        weightsFingerprint, domainVocabulary, slotVocabulary,
        dispositionVocabulary, axisVocabularies, capabilities,
        disposition, jurisdiction, dataHandlingPolicy, briefing,
        providerConfigs);
}
```

- [ ] **Step 9: Fix compilation — update all DeploymentGoals call sites**

The `DeploymentGoals` constructor now has 6 parameters instead of 5. Search for all call sites in ops and update them to pass `List.of()` for `adaptations`:

Use IntelliJ MCP `ide_find_references` on `DeploymentGoals` constructor to find all call sites. Each needs the 6th parameter added. This includes `DeploymentGoalLoader`, test fixtures, and `DeploymentGoalLoaderTest`.

- [ ] **Step 10: Run tests — verify they pass**

Run: `mvn --batch-mode -pl api -Dtest="AgentNodeSpecWithAgentIdTest,AdaptationRuleSpecTest" test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all tests PASS

- [ ] **Step 11: Run full ops build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 12: Commit**

```bash
git add api/src/main/java/io/casehub/ops/api/deployment/AdaptationRuleSpec.java api/src/main/java/io/casehub/ops/api/deployment/AdaptationTrigger.java api/src/main/java/io/casehub/ops/api/deployment/AdaptationActionSpec.java api/src/main/java/io/casehub/ops/api/deployment/DeploymentGoals.java api/src/main/java/io/casehub/ops/api/deployment/AgentNodeSpec.java api/src/test/java/io/casehub/ops/api/deployment/AdaptationRuleSpecTest.java api/src/test/java/io/casehub/ops/api/deployment/AgentNodeSpecWithAgentIdTest.java
git add -u  # catch call-site fixes
git commit -m "feat(#25): AdaptationRuleSpec types, DeploymentGoals.adaptations, AgentNodeSpec.withAgentId()"
```

---

### Task 4: AdaptationRule domain logic — rule evaluation and graph mutation

**Repo:** casehub-ops
**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/adaptation/AdaptationRule.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/adaptation/ScaleAction.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/adaptation/AddAction.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/adaptation/UpdateAction.java`
- Test: `deployment/src/test/java/io/casehub/ops/deployment/adaptation/AdaptationRuleTest.java`
- Test: `deployment/src/test/java/io/casehub/ops/deployment/adaptation/ScaleActionTest.java`
- Test: `deployment/src/test/java/io/casehub/ops/deployment/adaptation/UpdateActionTest.java`

**Interfaces:**
- Consumes: `AdaptationRuleSpec`, `AdaptationActionSpec.*`, `ActiveSituation`, `DesiredStateGraph`, `DesiredNode`, `NodeId`, `AgentNodeSpec.withAgentId()`
- Produces: `AdaptationRule.fromSpecs(List<AdaptationRuleSpec>)` returns `List<AdaptationRule>`. `AdaptationRule.apply(DesiredStateGraph, ActiveSituation)` returns mutated `DesiredStateGraph`. `AdaptationRule.targetNodeIds(DesiredStateGraph)` returns `Set<NodeId>`.

- [ ] **Step 1: Write ScaleAction test**

Tests should cover:
- Instance count calculation with normalized confidence: confidence 0.7 with minConfidence 0.7 → effective 0.0 → min instances. Confidence 1.0 → effective 1.0 → max instances. Confidence 0.85 with min=0.7 → effective 0.5 → mid-range.
- Derived instance IDs use `~` separator: `risk-agent~2`, `risk-agent~3`
- Base node is always present (instance 1 = the base node itself)
- Scale-down removes highest-numbered first (LIFO)
- Graph mutation: `withNode()` for additions, `withoutNode()` for removals

- [ ] **Step 2: Write UpdateAction test**

Tests should cover:
- Jackson tree-merge: base spec with threshold=0.7, update with threshold=0.9 → merged spec has threshold=0.9 and all other fields from base
- Works for all DeploymentNodeSpec types (at minimum TrustPolicyNodeSpec)
- Target resolution: finds node by nodeId in graph

- [ ] **Step 3: Write AdaptationRule.fromSpecs test**

Tests should cover:
- Parse-time validation: scale target contains `~` → exception
- Parse-time validation: NodeId collision with base topology (deferred to apply-time since base graph isn't available at parse-time — adjust test)
- Empty adaptations → empty rules list

- [ ] **Step 4: Run tests — verify they fail**

Run: `mvn --batch-mode -pl deployment -am -Dtest="ScaleActionTest,UpdateActionTest,AdaptationRuleTest" test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure

- [ ] **Step 5: Implement ScaleAction**

Core logic:
```java
public DesiredStateGraph apply(DesiredStateGraph graph, ActiveSituation situation,
                                double minConfidence) {
    double effective = Math.max(0.0, Math.min(1.0,
        (situation.confidence() - minConfidence) / (1.0 - minConfidence)));
    int instanceCount = Math.max(spec.min(),
        Math.min(spec.max(), spec.min() + (int)((spec.max() - spec.min()) * effective)));

    NodeId baseId = NodeId.of(spec.target());
    DesiredNode baseNode = graph.nodes().get(baseId);
    if (baseNode == null) return graph;
    if (!(baseNode.spec() instanceof AgentNodeSpec agentSpec)) return graph;

    DesiredStateGraph result = graph;

    // Add needed instances
    for (int i = 2; i <= instanceCount; i++) {
        String derivedId = spec.target() + "~" + i;
        NodeId nid = NodeId.of(derivedId);
        if (!result.nodes().containsKey(nid)) {
            DesiredNode derived = new DesiredNode(
                nid, baseNode.type(), agentSpec.withAgentId(derivedId),
                baseNode.requiresHuman());
            result = result.withNode(derived);
        }
    }

    // Remove excess instances (LIFO)
    for (int i = spec.max(); i > instanceCount; i--) {
        NodeId nid = NodeId.of(spec.target() + "~" + i);
        if (result.nodes().containsKey(nid)) {
            result = result.withoutNode(nid);
        }
    }
    return result;
}
```

- [ ] **Step 6: Implement UpdateAction**

Core logic using Jackson tree-merge:
```java
public DesiredStateGraph apply(DesiredStateGraph graph, ObjectMapper mapper) {
    NodeId targetId = NodeId.of(spec.target());
    DesiredNode node = graph.nodes().get(targetId);
    if (node == null) return graph;

    ObjectNode base = mapper.valueToTree(node.spec());
    ObjectNode overrides = mapper.valueToTree(spec.fields());
    base.setAll(overrides);
    NodeSpec merged = mapper.treeToValue(base, node.spec().getClass());

    return graph.withMutation(new GraphMutation.UpdateNode(targetId, merged));
}
```

- [ ] **Step 7: Implement AddAction**

Delegates to `DeploymentGoalCompiler.compile()` for the inline `DeploymentGoals`, then overlays onto the existing graph.

- [ ] **Step 8: Implement AdaptationRule**

Wraps `AdaptationRuleSpec` with the apply/targetNodeIds methods, dispatching to ScaleAction/AddAction/UpdateAction.

- [ ] **Step 9: Run tests — verify they pass**

Run: `mvn --batch-mode -pl deployment -am -Dtest="ScaleActionTest,UpdateActionTest,AdaptationRuleTest" test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all tests PASS

- [ ] **Step 10: Run full ops build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/adaptation/ deployment/src/test/java/io/casehub/ops/deployment/adaptation/
git commit -m "feat(#25): AdaptationRule domain logic — scale, add, update actions with graph mutation"
```

---

### Task 5: AdaptiveTopologyManager — situation-driven recompilation

**Repo:** casehub-ops
**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/adaptation/AdaptiveTopologyManager.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/adaptation/TenantAdaptationState.java`
- Test: `deployment/src/test/java/io/casehub/ops/deployment/adaptation/AdaptiveTopologyManagerTest.java`

**Interfaces:**
- Consumes: `SituationSource`, `SituationChangeEvent`, `ReconciliationLoop` (start, updateDesired, requestReconciliation), `DeploymentGoalCompiler`, `AdaptationRule`, `DesiredStateGraphFactory`
- Produces: `AdaptiveTopologyManager.initialize(String tenancyId, DeploymentGoals goals)`, `onSituationChange(@ObservesAsync SituationChangeEvent event)`

- [ ] **Step 1: Write AdaptiveTopologyManager test**

Tests should cover:
- `initialize()` compiles base topology and calls `reconciliationLoop.start()`
- `onSituationChange()` with matching situation → graph recompiled with adaptations, `updateDesired()` and `requestReconciliation()` called
- `onSituationChange()` with no matching situation → no update
- Hysteresis: confidence between `deactivateBelow` and `minConfidence` while active → stays active
- Hysteresis: confidence drops below `deactivateBelow` → deactivates
- Cooldown: rapid situation changes within cooldown window → state doesn't flip
- Situation disappears → adaptations removed on next recompilation
- Unknown tenancyId in event → no-op

Use mock `ReconciliationLoop` (or spy) to verify `start()`, `updateDesired()`, `requestReconciliation()` calls. Use a test `SituationSource` that returns controlled situations.

- [ ] **Step 2: Run tests — verify they fail**

- [ ] **Step 3: Implement TenantAdaptationState**

Per-tenant mutable state: goals, parsed rules, hysteresis tracking (`activePerRule`, `lastChangePerRule`). Methods: `shouldActivate(AdaptationRule, ActiveSituation)`, `clearAbsentSituations(Set<String>)`.

Implementation matches the spec's `TenantAdaptationState` inner class.

- [ ] **Step 4: Implement AdaptiveTopologyManager**

`@ApplicationScoped` bean. `ConcurrentHashMap<String, TenantAdaptationState>` for per-tenant state. `@ObservesAsync SituationChangeEvent` triggers recompilation. `synchronized(state)` for per-tenant serialization. `compileAdapted()` method that applies rules with conflict detection.

Implementation matches the spec's `AdaptiveTopologyManager` class.

- [ ] **Step 5: Run tests — verify they pass**

- [ ] **Step 6: Run full ops build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 7: Add periodic situation re-poll**

AdaptiveTopologyManager should periodically re-poll situations as a safety net for lost CDI events. Use a `ScheduledExecutorService` (single-thread, daemon) that runs every 5 minutes (matching ReconciliationLoop's resync interval). For each tenant in `tenantStates`, call `compileAdapted()` and compare the result to the current desired graph — if different, call `updateDesired()` + `requestReconciliation()`.

Add a test: with a mock `SituationSource` that returns a situation WITHOUT firing a `SituationChangeEvent`, verify that the periodic re-poll detects the situation and triggers recompilation.

- [ ] **Step 8: Run full ops build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/adaptation/AdaptiveTopologyManager.java deployment/src/main/java/io/casehub/ops/deployment/adaptation/TenantAdaptationState.java deployment/src/test/java/io/casehub/ops/deployment/adaptation/AdaptiveTopologyManagerTest.java
git commit -m "feat(#25): AdaptiveTopologyManager — situation-driven topology recompilation with hysteresis"
```

---

### Task 6: YAML integration test — adaptive deployment topology end-to-end

**Repo:** casehub-ops
**Files:**
- Create: `deployment/src/test/resources/test-deployment/adaptive-topology.yaml`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/adaptation/AdaptiveTopologyIntegrationTest.java`

**Interfaces:**
- Consumes: all of Tasks 1-5 — full stack from YAML parsing through graph mutation
- Produces: validation that the full pipeline works: load YAML → parse adaptations → compile base → apply adaptations from mock situations → verify graph contains expected scaled/added/updated nodes

- [ ] **Step 1: Create adaptive test YAML**

`deployment/src/test/resources/test-deployment/adaptive-topology.yaml`:
```yaml
agents:
  - spec:
      agentId: triage-agent
      name: Alert Triage
      slot: worker
  - spec:
      agentId: risk-agent
      name: Risk Monitor
      slot: worker

channels:
  - spec:
      name: trading/work
      semantic: APPEND

trust:
  - spec:
      capability: review
      threshold: 0.7
      minimumObservations: 5
      borderlineMargin: 0.1
      blendFactor: 0.5
      bootstrapEscalationRequired: false

adaptations:
  - name: scale-risk-on-volatility
    trigger:
      situation: volatility-spike
      minConfidence: 0.7
      deactivateBelow: 0.5
    actions:
      - type: scale
        target: risk-agent
        min: 1
        max: 5

  - name: tighten-trust-on-anomaly
    trigger:
      situation: market-anomaly
      minConfidence: 0.6
    actions:
      - type: update
        target: review
        nodeType: trust
        fields:
          threshold: 0.9
          bootstrapEscalationRequired: true
```

- [ ] **Step 2: Write integration test**

Tests should cover:
- Load YAML → `DeploymentGoals` has 2 adaptation rules
- Compile base → 3 nodes (triage-agent, risk-agent, trading/work channel + trust policy)
- No situations active → graph unchanged from base
- `volatility-spike` active at confidence 0.85 → risk-agent scaled to 3 instances (risk-agent, risk-agent~2, risk-agent~3)
- `market-anomaly` active at confidence 0.7 → trust policy threshold updated to 0.9
- Both situations active → both adaptations applied
- Situation resolves → graph returns to base

- [ ] **Step 3: Run test — verify it fails**

- [ ] **Step 4: Implement (should pass with existing code from Tasks 3-5)**

If YAML parsing of `adaptations:` requires `DeploymentGoalLoader` changes, make them here. The `@JsonIgnoreProperties(ignoreUnknown = true)` on `DeploymentGoals` means existing YAML without `adaptations:` still parses correctly.

- [ ] **Step 5: Run test — verify it passes**

Run: `mvn --batch-mode -pl deployment -am -Dtest=AdaptiveTopologyIntegrationTest test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: Run full ops build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add deployment/src/test/resources/test-deployment/adaptive-topology.yaml deployment/src/test/java/io/casehub/ops/deployment/adaptation/AdaptiveTopologyIntegrationTest.java
git commit -m "test(#25): adaptive topology integration test — YAML parsing through graph mutation"
```

---

### Task 7: Install desiredstate SNAPSHOT + final validation

**Repo:** casehub-desiredstate, then casehub-ops
**Files:**
- No new files — publish SNAPSHOT and validate cross-repo integration

- [ ] **Step 1: Install desiredstate SNAPSHOT**

Run: `mvn -C /Users/mdproctor/claude/casehub/desiredstate --batch-mode install`
Expected: BUILD SUCCESS — publishes `casehub-desiredstate-api` and `casehub-desiredstate-runtime` SNAPSHOTs to local Maven repo

- [ ] **Step 2: Verify ops resolves new desiredstate types**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — ops compiles against the new `SituationSource`, `ActiveSituation`, `SituationChangeEvent`, `ReconciliationLoop.requestReconciliation()`

- [ ] **Step 3: Verify all tests pass across both repos**

Run both:
```bash
mvn -C /Users/mdproctor/claude/casehub/desiredstate --batch-mode install
mvn --batch-mode install
```
Expected: both BUILD SUCCESS

- [ ] **Step 4: Commit any remaining adjustments**

If Step 2/3 revealed integration issues (import adjustments, version alignment), commit the fixes.

---

## File Structure Summary

### casehub-desiredstate (Task 1-2)

```
api/src/main/java/io/casehub/desiredstate/api/
  SituationSource.java          (NEW — SPI interface)
  ActiveSituation.java          (NEW — record)
  SituationChangeEvent.java     (NEW — CDI event record)
api/src/test/java/io/casehub/desiredstate/api/
  ActiveSituationTest.java      (NEW)

runtime/src/main/java/io/casehub/desiredstate/runtime/
  DefaultSituationSource.java   (NEW — @DefaultBean)
  ReconciliationLoop.java       (MODIFIED — add requestReconciliation())
runtime/src/test/java/io/casehub/desiredstate/runtime/
  DefaultSituationSourceTest.java                    (NEW)
  ReconciliationLoopRequestReconciliationTest.java   (NEW)
```

### casehub-ops (Task 3-6)

```
api/src/main/java/io/casehub/ops/api/deployment/
  AdaptationRuleSpec.java       (NEW — YAML record)
  AdaptationTrigger.java        (NEW — trigger config)
  AdaptationActionSpec.java     (NEW — sealed action hierarchy)
  DeploymentGoals.java          (MODIFIED — add adaptations field)
  AgentNodeSpec.java            (MODIFIED — add withAgentId())
api/src/test/java/io/casehub/ops/api/deployment/
  AdaptationRuleSpecTest.java       (NEW)
  AgentNodeSpecWithAgentIdTest.java (NEW)

deployment/src/main/java/io/casehub/ops/deployment/adaptation/
  AdaptationRule.java               (NEW — domain logic)
  ScaleAction.java                  (NEW)
  AddAction.java                    (NEW)
  UpdateAction.java                 (NEW)
  AdaptiveTopologyManager.java      (NEW — @ApplicationScoped)
  TenantAdaptationState.java        (NEW)
deployment/src/test/java/io/casehub/ops/deployment/adaptation/
  AdaptationRuleTest.java           (NEW)
  ScaleActionTest.java              (NEW)
  UpdateActionTest.java             (NEW)
  AdaptiveTopologyManagerTest.java  (NEW)
  AdaptiveTopologyIntegrationTest.java (NEW)
deployment/src/test/resources/test-deployment/
  adaptive-topology.yaml            (NEW)
```
