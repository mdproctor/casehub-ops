# Scaling Trigger Mechanism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #56 — feat: scaling trigger mechanism — what writes scalingRequired/scalingSpec
**Issue group:** #56

**Goal:** Wire scaling triggers (REST API + RAS situations) to the application-lifecycle case blackboard, so the existing scaling-event child case receives scaling requests.

**Architecture:** Three new beans — `SituationScalingEvaluator` (evaluates rules on situation changes), `ScalingSignalBridge` (writes to case blackboard), `ScalingResource` (REST endpoint) — unified through an internal `ScalingRequestedEvent` CDI event. Scaling rules are declared on `ServiceDefinition`. `CaseSignaler` is extracted to a shared interface. Blackboard path is unified to `.scalingRequired` (spec IS the trigger).

**Tech Stack:** Java 21, Quarkus, CDI, RAS API (`casehub-ras-api`), Mutiny, JUnit 5, AssertJ

## Global Constraints

- Pre-release: breaking changes are free — fix the design, never protect callers
- All new classes in `io.casehub.ops.app` packages (app module)
- `ras-api` dependency must be added to the app module's `pom.xml`
- IntelliJ MCP for all .java file operations — never bash grep/Edit
- TDD: failing test first, then minimal implementation
- Cooldown is enforced at evaluator/resource level, NOT in the child case

---

### Task 1: Data Model — ScalingRule, ServiceDefinition extension, CaseSignaler extraction

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/model/ScalingRule.java`
- Create: `app/src/test/java/io/casehub/ops/app/model/ScalingRuleTest.java`
- Create: `app/src/main/java/io/casehub/ops/app/service/CaseSignaler.java`
- Modify: `app/src/main/java/io/casehub/ops/app/model/ServiceDefinition.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/DriftSignalBridge.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java:136` (constructor call)
- Modify: `app/src/test/java/io/casehub/ops/app/model/ServiceDefinitionTest.java`
- Modify: `app/src/test/java/io/casehub/ops/app/service/UpdateServiceReplicasTest.java:199` (constructor call)
- Modify: `app/src/test/java/io/casehub/ops/app/goal/ApplicationGoalCompilerTest.java:116` (constructor call)
- Modify: `app/pom.xml` (add `casehub-ras-api` dependency)
- Test: `app/src/test/java/io/casehub/ops/app/model/ScalingRuleTest.java`

**Interfaces:**
- Produces: `ScalingRule(String situationId, double minConfidence, int minReplicas, int maxReplicas, Duration cooldownPeriod)` — used by Tasks 2, 3, 4
- Produces: `ServiceDefinition` gains `List<ScalingRule> scalingRules` — 11th field, used everywhere
- Produces: `CaseSignaler` functional interface `void signal(UUID caseId, String path, Object value)` — used by Tasks 2, 3
- Produces: `ScalingRule.computeTarget(double confidence)` → `int` — used by Task 2

- [ ] **Step 1: Add `casehub-ras-api` dependency to app/pom.xml**

Add to `app/pom.xml` dependencies section:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-ras-api</artifactId>
</dependency>
```

- [ ] **Step 2: Write ScalingRule tests**

Create `app/src/test/java/io/casehub/ops/app/model/ScalingRuleTest.java`:

```java
package io.casehub.ops.app.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ScalingRuleTest {

    @Test
    void confidenceAtMinReturnsMinReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(0.5)).isEqualTo(2);
    }

    @Test
    void confidenceAtOneReturnsMaxReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(1.0)).isEqualTo(10);
    }

    @Test
    void confidenceMidwayReturnsProportional() {
        var rule = new ScalingRule("high-load", 0.0, 2, 10, null);
        assertThat(rule.computeTarget(0.5)).isEqualTo(6);
    }

    @Test
    void confidenceBelowMinClampsToMinReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(0.3)).isEqualTo(2);
    }

    @Test
    void confidenceAboveOneClampsToMaxReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(1.5)).isEqualTo(10);
    }

    @Test
    void equalMinMaxReturnsConstant() {
        var rule = new ScalingRule("high-load", 0.5, 5, 5, null);
        assertThat(rule.computeTarget(0.5)).isEqualTo(5);
        assertThat(rule.computeTarget(1.0)).isEqualTo(5);
    }

    @Test
    void cooldownPeriodIsNullable() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.cooldownPeriod()).isNull();
    }

    @Test
    void cooldownPeriodIsStored() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        assertThat(rule.cooldownPeriod()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsNullSituationId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ScalingRule(null, 0.5, 2, 10, null));
    }

    @Test
    void rejectsNegativeMinReplicas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", 0.5, -1, 10, null));
    }

    @Test
    void rejectsMaxLessThanMin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", 0.5, 10, 5, null));
    }

    @Test
    void rejectsMinConfidenceBelowZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", -0.1, 2, 10, null));
    }

    @Test
    void rejectsMinConfidenceAboveOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", 1.1, 2, 10, null));
    }
}
```

- [ ] **Step 3: Run tests — expect compile failure (ScalingRule does not exist)**

Run: `mvn --batch-mode -o test -pl app -Dtest=ScalingRuleTest`
Expected: COMPILATION ERROR

- [ ] **Step 4: Implement ScalingRule**

Create `app/src/main/java/io/casehub/ops/app/model/ScalingRule.java`:

```java
package io.casehub.ops.app.model;

import java.time.Duration;
import java.util.Objects;

public record ScalingRule(
        String situationId,
        double minConfidence,
        int minReplicas,
        int maxReplicas,
        Duration cooldownPeriod) {

    public ScalingRule {
        Objects.requireNonNull(situationId, "situationId");
        if (minConfidence < 0.0 || minConfidence > 1.0)
            throw new IllegalArgumentException("minConfidence must be 0.0-1.0, got: " + minConfidence);
        if (minReplicas < 0) throw new IllegalArgumentException("minReplicas must be >= 0");
        if (maxReplicas < minReplicas) throw new IllegalArgumentException("maxReplicas must be >= minReplicas");
    }

    public int computeTarget(double confidence) {
        double effective = Math.max(0.0, Math.min(1.0,
                (confidence - minConfidence) / (1.0 - minConfidence)));
        return Math.max(minReplicas, Math.min(maxReplicas,
                minReplicas + (int) ((maxReplicas - minReplicas) * effective)));
    }
}
```

- [ ] **Step 5: Run ScalingRuleTest — expect PASS**

Run: `mvn --batch-mode -o test -pl app -Dtest=ScalingRuleTest`
Expected: all tests pass

- [ ] **Step 6: Extract CaseSignaler to top-level interface**

Create `app/src/main/java/io/casehub/ops/app/service/CaseSignaler.java`:

```java
package io.casehub.ops.app.service;

import java.util.UUID;

@FunctionalInterface
public interface CaseSignaler {
    void signal(UUID caseId, String path, Object value);
}
```

Then modify `DriftSignalBridge.java`:
- Remove the inner `CaseSignaler` interface (lines 25-28)
- Change the field type from `CaseSignaler` to the new top-level `CaseSignaler` (already the same name, import resolves)

Use `ide_refactor_safe_delete` on the inner interface after verifying the new top-level one compiles.

- [ ] **Step 7: Add `scalingRules` field to ServiceDefinition**

Use `ide_edit_member` on `ServiceDefinition` to add the 11th field. The record becomes:

```java
public record ServiceDefinition(
        String serviceId,
        String name,
        String image,
        int replicas,
        List<PortMapping> ports,
        Map<String, String> env,
        ResourceRequirements resources,
        List<String> dependsOn,
        Optional<HealthCheckSpec> healthCheck,
        List<String> targetClusters,
        List<ScalingRule> scalingRules) {
```

Add to compact constructor:
```java
Objects.requireNonNull(scalingRules, "scalingRules");
scalingRules = List.copyOf(scalingRules);
```

- [ ] **Step 8: Fix all ServiceDefinition constructor call sites**

Every existing `new ServiceDefinition(...)` call needs `List.of()` as the 11th argument. Fix these call sites using `ide_edit_member` or `ide_replace_member`:

1. `ApplicationLifecycleService.updateServiceReplicas()` line 136 — add `sd.scalingRules()` (preserves existing rules)
2. `UpdateServiceReplicasTest.patchReplicas()` line 199 — add `sd.scalingRules()`
3. `ApplicationGoalCompilerTest.service()` line 116 — add `List.of()`
4. `ServiceDefinitionTest` lines 18, 38, 48, 62 — add `List.of()` to each constructor

- [ ] **Step 9: Add ServiceDefinition test for scalingRules round-trip**

Add test to `ServiceDefinitionTest.java`:

```java
@Test
void scalingRulesArePreserved() {
    var rules = List.of(
            new ScalingRule("high-load", 0.5, 2, 10, java.time.Duration.ofMinutes(5)));
    var sd = new ServiceDefinition(
            "web", "Web", "img:1.0", 2,
            List.of(), Map.of(),
            new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
            List.of(), Optional.empty(), List.of(), rules);
    assertThat(sd.scalingRules()).hasSize(1);
    assertThat(sd.scalingRules().get(0).situationId()).isEqualTo("high-load");
}
```

- [ ] **Step 10: Run full app test suite**

Run: `mvn --batch-mode -o test -pl app`
Expected: all tests pass (including existing tests with updated constructor calls)

- [ ] **Step 11: Commit**

```bash
git add -A && git commit -m "feat(#56): ScalingRule record, CaseSignaler extraction, ServiceDefinition 11th field"
```

---

### Task 2: SituationScalingEvaluator + ScalingEvaluatorSupport

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/service/ScalingRequestedEvent.java`
- Create: `app/src/main/java/io/casehub/ops/app/service/ScalingEvaluatorSupport.java`
- Create: `app/src/main/java/io/casehub/ops/app/service/SituationScalingEvaluator.java`
- Create: `app/src/test/java/io/casehub/ops/app/service/SituationScalingEvaluatorTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/service/SituationScalingEvaluatorTest.java`

**Interfaces:**
- Consumes: `ScalingRule.computeTarget(double)` from Task 1
- Consumes: `ScalingPolicy(int, int, Duration)` from existing code
- Consumes: `SituationSource.activeSituations(String)` from `casehub-ras-api`
- Consumes: `SituationChangeEvent` from `casehub-ras-api`
- Consumes: `ActiveSituation` from `casehub-ras-api`
- Produces: `ScalingRequestedEvent(UUID appCaseId, String applicationId, String tenancyId, String serviceId, int targetReplicas, int currentReplicas, String reason, ScalingPolicy policy)` — used by Tasks 3, 4
- Produces: `SituationScalingEvaluator.register(String tenancyId, UUID appCaseId, String applicationId, Map<String, Integer> baseReplicas)` — used by Task 5
- Produces: `SituationScalingEvaluator.deregister(String tenancyId, String applicationId)` — used by Task 5
- Produces: `SituationScalingEvaluator.isCoolingDown(String applicationId, String serviceId)` — used by Task 4
- Produces: `SituationScalingEvaluator.recordScalingTimestamp(String applicationId, String serviceId)` — used by Task 4

- [ ] **Step 1: Create ScalingRequestedEvent**

Create `app/src/main/java/io/casehub/ops/app/service/ScalingRequestedEvent.java`:

```java
package io.casehub.ops.app.service;

import io.casehub.ops.app.case_.ScalingPolicy;
import java.util.UUID;

public record ScalingRequestedEvent(
        UUID appCaseId,
        String applicationId,
        String tenancyId,
        String serviceId,
        int targetReplicas,
        int currentReplicas,
        String reason,
        ScalingPolicy policy) {}
```

- [ ] **Step 2: Create ScalingEvaluatorSupport**

Create `app/src/main/java/io/casehub/ops/app/service/ScalingEvaluatorSupport.java`:

```java
package io.casehub.ops.app.service;

import io.casehub.ops.app.entity.ApplicationEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class ScalingEvaluatorSupport {

    @Transactional
    public String loadServicesJson(UUID applicationId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        return app != null ? app.servicesJson : null;
    }
}
```

- [ ] **Step 3: Write SituationScalingEvaluator tests**

Create `app/src/test/java/io/casehub/ops/app/service/SituationScalingEvaluatorTest.java`. This is a large test class — the evaluator is the most complex component. Tests use a test-only constructor that accepts a `SituationSource` stub, an event sink (replacing CDI async), and a `ServicesJsonLoader` function (replacing `ScalingEvaluatorSupport`).

```java
package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.ops.app.case_.ScalingPolicy;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationChangeEvent.ChangeType;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationSource;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class SituationScalingEvaluatorTest {

    private List<ScalingRequestedEvent> firedEvents;
    private List<ActiveSituation> activeSituations;
    private String currentServicesJson;
    private SituationScalingEvaluator evaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        firedEvents = new CopyOnWriteArrayList<>();
        activeSituations = new CopyOnWriteArrayList<>();
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module());

        SituationSource source = tenancyId -> Uni.createFrom().item(List.copyOf(activeSituations));

        evaluator = new SituationScalingEvaluator(
                source,
                firedEvents::add,
                appId -> currentServicesJson,
                objectMapper);
    }

    @Test
    void situationMatchingRuleFiresEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).serviceId()).isEqualTo("web");
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(6); // 2 + (int)((10-2) * 0.6) = 6
    }

    @Test
    void situationBelowMinConfidenceNoEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.7, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.5));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void noRegisteredAppsNoEvent() {
        activeSituations.add(situation("high-load", "tenant-1", 0.8));
        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void serviceWithNoRulesSkipped() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesNoRules("web", 2);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void targetEqualsCurrent_noEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.0, 2, 2);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void multipleRulesMatchMaxWins() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        var rules = List.of(
                new ScalingRule("high-load", 0.5, 2, 8, null),
                new ScalingRule("peak-hours", 0.3, 2, 12, null));
        setServicesWithRules("web", 2, rules);
        activeSituations.add(situation("high-load", "tenant-1", 1.0));
        activeSituations.add(situation("peak-hours", "tenant-1", 1.0));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(12); // max(8, 12)
    }

    @Test
    void multipleRulesMergedPolicyUsesMinMinMaxMax() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        var rules = List.of(
                new ScalingRule("high-load", 0.5, 3, 8, Duration.ofMinutes(2)),
                new ScalingRule("peak-hours", 0.3, 1, 12, Duration.ofMinutes(5)));
        setServicesWithRules("web", 2, rules);
        activeSituations.add(situation("high-load", "tenant-1", 1.0));
        activeSituations.add(situation("peak-hours", "tenant-1", 1.0));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(firedEvents).hasSize(1);
        ScalingPolicy policy = firedEvents.get(0).policy();
        assertThat(policy.minReplicas()).isEqualTo(1);  // min(3, 1)
        assertThat(policy.maxReplicas()).isEqualTo(12); // max(8, 12)
        assertThat(policy.cooldownPeriod()).isEqualTo(Duration.ofMinutes(5)); // max
    }

    @Test
    void resolvedSituationScalesDown() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 8, "high-load", 0.5, 2, 10);
        // No active situations — resolved

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.RESOLVED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(2); // base replicas
    }

    @Test
    void discardedSameAsResolved() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 8, "high-load", 0.5, 2, 10);

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.DISCARDED));

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).targetReplicas()).isEqualTo(2);
    }

    @Test
    void cooldownSuppressesEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1);

        // Second event within cooldown
        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1); // still 1 — suppressed
    }

    @Test
    void afterCooldownExpiresEventFires() throws InterruptedException {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMillis(50));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(1);

        Thread.sleep(100);

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).hasSize(2);
    }

    @Test
    void deregisteredAppNoLongerEvaluated() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10);
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.deregister("tenant-1", "app-1");

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void isCoolingDownReturnsTrueAfterEvent() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        activeSituations.add(situation("high-load", "tenant-1", 0.8));

        evaluator.onSituationChange(event("tenant-1", "high-load", ChangeType.TRIGGERED));

        assertThat(evaluator.isCoolingDown("app-1", "web")).isTrue();
    }

    @Test
    void recordScalingTimestampEnablesCooldownForRestPath() {
        registerApp("tenant-1", "app-1", Map.of("web", 2));
        setServicesWithRule("web", 2, "high-load", 0.5, 2, 10, Duration.ofMinutes(5));

        evaluator.recordScalingTimestamp("app-1", "web");

        assertThat(evaluator.isCoolingDown("app-1", "web")).isTrue();
    }

    // --- helpers ---

    private UUID registerApp(String tenancyId, String appId, Map<String, Integer> baseReplicas) {
        UUID caseId = UUID.randomUUID();
        evaluator.register(tenancyId, caseId, appId, baseReplicas);
        return caseId;
    }

    private void setServicesWithRule(String serviceId, int replicas,
                                     String situationId, double minConf,
                                     int minRep, int maxRep) {
        setServicesWithRule(serviceId, replicas, situationId, minConf, minRep, maxRep, null);
    }

    private void setServicesWithRule(String serviceId, int replicas,
                                     String situationId, double minConf,
                                     int minRep, int maxRep, Duration cooldown) {
        var rules = List.of(new ScalingRule(situationId, minConf, minRep, maxRep, cooldown));
        setServicesWithRules(serviceId, replicas, rules);
    }

    private void setServicesWithRules(String serviceId, int replicas, List<ScalingRule> rules) {
        var sd = new ServiceDefinition(serviceId, serviceId, "img:1.0", replicas,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(), rules);
        try {
            currentServicesJson = objectMapper.writeValueAsString(List.of(sd));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setServicesNoRules(String serviceId, int replicas) {
        setServicesWithRules(serviceId, replicas, List.of());
    }

    private ActiveSituation situation(String situationId, String tenancyId, double confidence) {
        return new ActiveSituation(situationId, "corr-1", tenancyId, confidence,
                Map.of(), Instant.now(), Instant.now(), 1);
    }

    private SituationChangeEvent event(String tenancyId, String situationId, ChangeType type) {
        return new SituationChangeEvent(tenancyId, situationId, "corr-1", type,
                new SituationContext(Map.of()));
    }
}
```

- [ ] **Step 4: Run tests — expect compile failure**

Run: `mvn --batch-mode -o test -pl app -Dtest=SituationScalingEvaluatorTest`
Expected: COMPILATION ERROR (SituationScalingEvaluator doesn't exist)

- [ ] **Step 5: Implement SituationScalingEvaluator**

Create `app/src/main/java/io/casehub/ops/app/service/SituationScalingEvaluator.java`:

```java
package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ops.app.case_.ScalingPolicy;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationSource;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class SituationScalingEvaluator {

    private static final Logger LOG = Logger.getLogger(SituationScalingEvaluator.class.getName());
    private static final long POLL_INTERVAL_MINUTES = 5;

    record ScalingRegistration(UUID appCaseId, String applicationId,
                               Map<String, Integer> baseReplicas) {}

    private final SituationSource situationSource;
    private final Consumer<ScalingRequestedEvent> eventSink;
    private final Function<UUID, String> servicesJsonLoader;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ScalingRegistration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastScalingTimestamps = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pollScheduler;
    private volatile ScheduledFuture<?> pollFuture;

    @Inject
    public SituationScalingEvaluator(
            SituationSource situationSource,
            Event<ScalingRequestedEvent> cdiEvent,
            ScalingEvaluatorSupport support,
            ObjectMapper objectMapper) {
        this(situationSource,
             event -> cdiEvent.fireAsync(event),
             appId -> support.loadServicesJson(appId),
             objectMapper);
    }

    SituationScalingEvaluator(
            SituationSource situationSource,
            Consumer<ScalingRequestedEvent> eventSink,
            Function<UUID, String> servicesJsonLoader,
            ObjectMapper objectMapper) {
        this.situationSource = Objects.requireNonNull(situationSource);
        this.eventSink = Objects.requireNonNull(eventSink);
        this.servicesJsonLoader = Objects.requireNonNull(servicesJsonLoader);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.pollScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "scaling-evaluator-poll");
            t.setDaemon(true);
            return t;
        });
        startPeriodicPoll();
    }

    public void register(String tenancyId, UUID appCaseId, String applicationId,
                          Map<String, Integer> baseReplicas) {
        String key = tenancyId + ":" + applicationId;
        registrations.put(key, new ScalingRegistration(appCaseId, applicationId,
                Map.copyOf(baseReplicas)));
    }

    public void deregister(String tenancyId, String applicationId) {
        registrations.remove(tenancyId + ":" + applicationId);
    }

    public boolean isCoolingDown(String applicationId, String serviceId) {
        String key = applicationId + ":" + serviceId;
        Instant last = lastScalingTimestamps.get(key);
        if (last == null) return false;
        // Check against all rules for this service — would need servicesJson
        // For the REST path, just check if a timestamp exists within a reasonable window
        return Duration.between(last, Instant.now()).toMinutes() < 60;
    }

    public boolean isCoolingDown(String applicationId, String serviceId,
                                  Duration cooldownPeriod) {
        if (cooldownPeriod == null) return false;
        String key = applicationId + ":" + serviceId;
        Instant last = lastScalingTimestamps.get(key);
        if (last == null) return false;
        return Duration.between(last, Instant.now()).compareTo(cooldownPeriod) < 0;
    }

    public void recordScalingTimestamp(String applicationId, String serviceId) {
        lastScalingTimestamps.put(applicationId + ":" + serviceId, Instant.now());
    }

    public void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        evaluateForTenant(event.tenancyId());
    }

    void pollAllTenants() {
        registrations.keySet().stream()
                .map(key -> key.substring(0, key.indexOf(':')))
                .distinct()
                .forEach(this::evaluateForTenant);
    }

    private void evaluateForTenant(String tenancyId) {
        List<ActiveSituation> situations;
        try {
            situations = situationSource.activeSituations(tenancyId).await().indefinitely();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to query active situations for " + tenancyId, e);
            return;
        }

        for (var entry : registrations.entrySet()) {
            if (!entry.getKey().startsWith(tenancyId + ":")) continue;
            ScalingRegistration reg = entry.getValue();

            synchronized (reg) {
                evaluateRegistration(tenancyId, reg, situations);
            }
        }
    }

    private void evaluateRegistration(String tenancyId, ScalingRegistration reg,
                                       List<ActiveSituation> situations) {
        String servicesJson = servicesJsonLoader.apply(UUID.fromString(reg.applicationId()));
        if (servicesJson == null) return;

        List<ServiceDefinition> services;
        try {
            services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse servicesJson for " + reg.applicationId(), e);
            return;
        }

        for (ServiceDefinition sd : services) {
            if (sd.scalingRules().isEmpty()) continue;

            int targetReplicas = -1;
            int mergedMinReplicas = Integer.MAX_VALUE;
            int mergedMaxReplicas = 0;
            Duration mergedCooldown = null;
            int matchCount = 0;

            for (ScalingRule rule : sd.scalingRules()) {
                var match = situations.stream()
                        .filter(s -> s.situationId().equals(rule.situationId()))
                        .filter(s -> s.confidence() >= rule.minConfidence())
                        .findFirst();
                if (match.isPresent()) {
                    int computed = rule.computeTarget(match.get().confidence());
                    if (computed > targetReplicas) {
                        targetReplicas = computed;
                    }
                    mergedMinReplicas = Math.min(mergedMinReplicas, rule.minReplicas());
                    mergedMaxReplicas = Math.max(mergedMaxReplicas, rule.maxReplicas());
                    if (rule.cooldownPeriod() != null) {
                        mergedCooldown = mergedCooldown == null ? rule.cooldownPeriod()
                                : rule.cooldownPeriod().compareTo(mergedCooldown) > 0
                                  ? rule.cooldownPeriod() : mergedCooldown;
                    }
                    matchCount++;
                }
            }

            if (matchCount == 0) {
                Integer base = reg.baseReplicas().get(sd.serviceId());
                if (base != null && base != sd.replicas()) {
                    targetReplicas = base;
                    mergedMinReplicas = 0;
                    mergedMaxReplicas = Integer.MAX_VALUE;
                } else {
                    continue;
                }
            }

            if (targetReplicas == sd.replicas()) continue;

            Duration effectiveCooldown = maxCooldownForService(sd);
            if (isCoolingDown(reg.applicationId(), sd.serviceId(), effectiveCooldown)) continue;

            ScalingPolicy policy = matchCount > 0
                    ? new ScalingPolicy(mergedMinReplicas, mergedMaxReplicas, mergedCooldown)
                    : ScalingPolicy.UNBOUNDED;

            var event = new ScalingRequestedEvent(
                    reg.appCaseId(), reg.applicationId(), tenancyId,
                    sd.serviceId(), targetReplicas, sd.replicas(),
                    matchCount > 0 ? "situation-driven" : "situation-resolved",
                    policy);

            eventSink.accept(event);
            recordScalingTimestamp(reg.applicationId(), sd.serviceId());
        }
    }

    private Duration maxCooldownForService(ServiceDefinition sd) {
        Duration max = null;
        for (ScalingRule rule : sd.scalingRules()) {
            if (rule.cooldownPeriod() != null) {
                max = max == null ? rule.cooldownPeriod()
                        : rule.cooldownPeriod().compareTo(max) > 0 ? rule.cooldownPeriod() : max;
            }
        }
        return max;
    }

    private void startPeriodicPoll() {
        pollFuture = pollScheduler.scheduleAtFixedRate(() -> {
            try {
                pollAllTenants();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Periodic scaling evaluation poll failed", e);
            }
        }, POLL_INTERVAL_MINUTES, POLL_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        if (pollFuture != null) pollFuture.cancel(false);
        pollScheduler.shutdownNow();
    }
}
```

- [ ] **Step 6: Run SituationScalingEvaluatorTest — expect PASS**

Run: `mvn --batch-mode -o test -pl app -Dtest=SituationScalingEvaluatorTest`
Expected: all tests pass

- [ ] **Step 7: Run full app test suite**

Run: `mvn --batch-mode -o test -pl app`
Expected: all tests pass

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(#56): SituationScalingEvaluator with confidence-proportional rules and cooldown"
```

---

### Task 3: ScalingSignalBridge

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/service/ScalingSignalBridge.java`
- Create: `app/src/test/java/io/casehub/ops/app/service/ScalingSignalBridgeTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/service/ScalingSignalBridgeTest.java`

**Interfaces:**
- Consumes: `ScalingRequestedEvent` from Task 2
- Consumes: `CaseSignaler` from Task 1
- Consumes: `CaseHubRuntime.signal(UUID, String, Object)` from engine API
- Produces: blackboard signal `"scalingRequired"` with full spec map — consumed by the existing scaling-event child case

- [ ] **Step 1: Write ScalingSignalBridge tests**

Create `app/src/test/java/io/casehub/ops/app/service/ScalingSignalBridgeTest.java`:

```java
package io.casehub.ops.app.service;

import io.casehub.ops.app.case_.ScalingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class ScalingSignalBridgeTest {

    private ScalingSignalBridge bridge;
    private java.util.List<SignalRecord> signals;

    @BeforeEach
    void setUp() {
        signals = new CopyOnWriteArrayList<>();
        bridge = new ScalingSignalBridge(
                (caseId, path, value) -> signals.add(new SignalRecord(caseId, path, value)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventSignalsCorrectCaseWithFullSpec() {
        UUID caseId = UUID.randomUUID();
        var event = new ScalingRequestedEvent(caseId, "app-1", "tenant-1",
                "web", 6, 3, "high-load", new ScalingPolicy(2, 10, Duration.ofMinutes(5)));

        bridge.onScalingRequested(event);

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(caseId);
        assertThat(signals.get(0).path()).isEqualTo("scalingRequired");

        Map<String, Object> spec = (Map<String, Object>) signals.get(0).value();
        assertThat(spec.get("serviceId")).isEqualTo("web");
        assertThat(spec.get("targetReplicas")).isEqualTo(6);
        assertThat(spec.get("currentReplicas")).isEqualTo(3);
        assertThat(spec.get("applicationId")).isEqualTo("app-1");
        assertThat(spec.get("tenancyId")).isEqualTo("tenant-1");
        assertThat(spec.get("reason")).isEqualTo("high-load");
        assertThat(spec.get("minReplicas")).isEqualTo(2);
        assertThat(spec.get("maxReplicas")).isEqualTo(10);
        assertThat(spec.get("cooldownSeconds")).isEqualTo(300L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unboundedPolicyOmitsCooldown() {
        UUID caseId = UUID.randomUUID();
        var event = new ScalingRequestedEvent(caseId, "app-1", "tenant-1",
                "web", 5, 2, "manual", ScalingPolicy.UNBOUNDED);

        bridge.onScalingRequested(event);

        Map<String, Object> spec = (Map<String, Object>) signals.get(0).value();
        assertThat(spec).doesNotContainKey("cooldownSeconds");
    }

    record SignalRecord(UUID caseId, String path, Object value) {}
}
```

- [ ] **Step 2: Run tests — expect compile failure**

Run: `mvn --batch-mode -o test -pl app -Dtest=ScalingSignalBridgeTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: Implement ScalingSignalBridge**

Create `app/src/main/java/io/casehub/ops/app/service/ScalingSignalBridge.java`:

```java
package io.casehub.ops.app.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ScalingSignalBridge {

    private static final Logger LOG = Logger.getLogger(ScalingSignalBridge.class.getName());

    private final CaseSignaler signaler;

    @Inject
    public ScalingSignalBridge(io.casehub.api.engine.CaseHubRuntime runtime) {
        this((caseId, path, value) -> runtime.signal(caseId, path, value));
    }

    ScalingSignalBridge(CaseSignaler signaler) {
        this.signaler = signaler;
    }

    void onScalingRequested(@ObservesAsync ScalingRequestedEvent event) {
        var spec = new LinkedHashMap<String, Object>();
        spec.put("serviceId", event.serviceId());
        spec.put("targetReplicas", event.targetReplicas());
        spec.put("currentReplicas", event.currentReplicas());
        spec.put("applicationId", event.applicationId());
        spec.put("tenancyId", event.tenancyId());
        spec.put("reason", event.reason());
        spec.put("minReplicas", event.policy().minReplicas());
        spec.put("maxReplicas", event.policy().maxReplicas());
        if (event.policy().cooldownPeriod() != null) {
            spec.put("cooldownSeconds", event.policy().cooldownPeriod().toSeconds());
        }

        try {
            signaler.signal(event.appCaseId(), "scalingRequired", spec);
            LOG.fine(() -> "Signaled scaling for app " + event.applicationId()
                    + " service " + event.serviceId()
                    + " target=" + event.targetReplicas());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to signal scaling for case " + event.appCaseId(), e);
        }
    }
}
```

- [ ] **Step 4: Run ScalingSignalBridgeTest — expect PASS**

Run: `mvn --batch-mode -o test -pl app -Dtest=ScalingSignalBridgeTest`
Expected: all tests pass

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(#56): ScalingSignalBridge — translates ScalingRequestedEvent to blackboard signal"
```

---

### Task 4: ScalingResource (REST endpoint)

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/rest/ScalingResource.java`
- Create: `app/src/main/java/io/casehub/ops/app/rest/dto/ScaleServiceRequest.java`
- Create: `app/src/test/java/io/casehub/ops/app/rest/ScalingResourceTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/rest/ScalingResourceTest.java`

**Interfaces:**
- Consumes: `ScalingRequestedEvent` from Task 2
- Consumes: `SituationScalingEvaluator.isCoolingDown()` from Task 2
- Consumes: `SituationScalingEvaluator.recordScalingTimestamp()` from Task 2
- Consumes: `ApplicationEntity` from existing code
- Consumes: `ServiceDefinitionParser` from existing code
- Produces: `POST /api/applications/{id}/services/{serviceId}/scale` endpoint

- [ ] **Step 1: Create ScaleServiceRequest DTO**

Create `app/src/main/java/io/casehub/ops/app/rest/dto/ScaleServiceRequest.java`:

```java
package io.casehub.ops.app.rest.dto;

public record ScaleServiceRequest(int targetReplicas, String reason) {}
```

- [ ] **Step 2: Write ScalingResource tests**

Create `app/src/test/java/io/casehub/ops/app/rest/ScalingResourceTest.java`. Tests use the REST resource directly (not @QuarkusTest) to avoid full CDI bootstrap:

```java
package io.casehub.ops.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.ops.app.case_.ScalingPolicy;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.app.rest.dto.ScaleServiceRequest;
import io.casehub.ops.app.service.ScalingRequestedEvent;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class ScalingResourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());

    @Test
    void validRequestReturns202() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);
        UUID appId = UUID.randomUUID();

        var response = resource.scale(appId, "web",
                new ScaleServiceRequest(5, "manual"),
                testApp(appId, "RUNNING", servicesJson("web", 2, List.of())));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).targetReplicas()).isEqualTo(5);
        assertThat(events.get(0).serviceId()).isEqualTo("web");
    }

    @Test
    void wrongStatusReturns409() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);
        UUID appId = UUID.randomUUID();

        var response = resource.scale(appId, "web",
                new ScaleServiceRequest(5, "manual"),
                testApp(appId, "DRAFT", servicesJson("web", 2, List.of())));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(events).isEmpty();
    }

    @Test
    void nullEngineCaseIdReturns409() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);
        UUID appId = UUID.randomUUID();

        var response = resource.scale(appId, "web",
                new ScaleServiceRequest(5, "manual"),
                testAppNoCaseId(appId, "RUNNING", servicesJson("web", 2, List.of())));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void unknownServiceReturns404() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);
        UUID appId = UUID.randomUUID();

        var response = resource.scale(appId, "nonexistent",
                new ScaleServiceRequest(5, "manual"),
                testApp(appId, "RUNNING", servicesJson("web", 2, List.of())));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void coolingDownReturns429() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var cooldownServices = new java.util.HashSet<String>();
        cooldownServices.add("app-1:web");
        var resource = buildResourceWithCooldown(events, cooldownServices);
        UUID appId = UUID.randomUUID();

        var response = resource.scale(appId, "web",
                new ScaleServiceRequest(5, "manual"),
                testApp(appId, "RUNNING", servicesJson("web", 2,
                        List.of(new ScalingRule("x", 0.5, 2, 10, Duration.ofMinutes(5))))));

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void serviceWithRulesIncludesWarningHeader() {
        var events = new CopyOnWriteArrayList<ScalingRequestedEvent>();
        var resource = buildResource(events);
        UUID appId = UUID.randomUUID();

        var response = resource.scale(appId, "web",
                new ScaleServiceRequest(5, "manual"),
                testApp(appId, "RUNNING", servicesJson("web", 2,
                        List.of(new ScalingRule("x", 0.5, 2, 10, null)))));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getHeaderString("X-Scaling-Warning")).isEqualTo("active-rules");
    }

    // --- helpers (test doubles for the resource's dependencies) ---

    // Note: these tests call the resource logic directly. The actual REST resource
    // uses CDI injection; tests use a package-private constructor or inline logic.
    // Full implementation details depend on the resource's internal structure.

    private ScalingResource buildResource(List<ScalingRequestedEvent> events) {
        return buildResourceWithCooldown(events, java.util.Set.of());
    }

    private ScalingResource buildResourceWithCooldown(List<ScalingRequestedEvent> events,
                                                       java.util.Set<String> coolingDown) {
        // ScalingResource will need a test-friendly constructor accepting event sink
        // and cooldown checker. Detailed below in the implementation step.
        return new ScalingResource(events::add,
                (appId, serviceId) -> coolingDown.contains(appId + ":" + serviceId),
                (appId, serviceId) -> coolingDown.add(appId + ":" + serviceId),
                objectMapper);
    }

    private String servicesJson(String serviceId, int replicas, List<ScalingRule> rules) {
        var sd = new ServiceDefinition(serviceId, serviceId, "img:1.0", replicas,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(), rules);
        try {
            return objectMapper.writeValueAsString(List.of(sd));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Stub application records for testing
    private TestApp testApp(UUID id, String status, String servicesJson) {
        return new TestApp(id, UUID.randomUUID(), status, servicesJson, "app-1");
    }

    private TestApp testAppNoCaseId(UUID id, String status, String servicesJson) {
        return new TestApp(id, null, status, servicesJson, "app-1");
    }

    record TestApp(UUID id, UUID engineCaseId, String status, String servicesJson, String applicationId) {}
}
```

- [ ] **Step 3: Implement ScalingResource**

Create `app/src/main/java/io/casehub/ops/app/rest/ScalingResource.java`:

```java
package io.casehub.ops.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ops.app.case_.ScalingPolicy;
import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ScalingRule;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.app.rest.dto.ScaleServiceRequest;
import io.casehub.ops.app.service.ScalingRequestedEvent;
import io.casehub.ops.app.service.ServiceDefinitionParser;
import io.casehub.ops.app.service.SituationScalingEvaluator;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

@Blocking
@ApplicationScoped
@Path("/api/applications/{appId}/services/{serviceId}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScalingResource {

    private final Consumer<ScalingRequestedEvent> eventSink;
    private final BiPredicate<String, String> cooldownChecker;
    private final BiConsumer<String, String> timestampRecorder;
    private final ObjectMapper objectMapper;

    @Inject
    public ScalingResource(Event<ScalingRequestedEvent> cdiEvent,
                           SituationScalingEvaluator evaluator,
                           ObjectMapper objectMapper) {
        this(event -> cdiEvent.fireAsync(event),
             evaluator::isCoolingDown,
             evaluator::recordScalingTimestamp,
             objectMapper);
    }

    ScalingResource(Consumer<ScalingRequestedEvent> eventSink,
                    BiPredicate<String, String> cooldownChecker,
                    BiConsumer<String, String> timestampRecorder,
                    ObjectMapper objectMapper) {
        this.eventSink = eventSink;
        this.cooldownChecker = cooldownChecker;
        this.timestampRecorder = timestampRecorder;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/scale")
    public Response scale(@PathParam("appId") UUID appId,
                          @PathParam("serviceId") String serviceId,
                          ScaleServiceRequest request,
                          @Context ContainerRequestContext ctx) {
        // In production, load from DB. In tests, passed directly.
        var app = ApplicationEntity.<ApplicationEntity>findById(appId);
        if (app == null) return Response.status(404).build();
        return scale(appId, serviceId, request, app);
    }

    Response scale(UUID appId, String serviceId, ScaleServiceRequest request,
                   Object appRecord) {
        // Extract fields — supports both ApplicationEntity and test record
        UUID engineCaseId;
        String status;
        String servicesJson;
        String applicationId;

        if (appRecord instanceof ApplicationEntity app) {
            engineCaseId = app.engineCaseId;
            status = app.status.name();
            servicesJson = app.servicesJson;
            applicationId = app.id.toString();
        } else if (appRecord instanceof io.casehub.ops.app.rest.ScalingResourceTest.TestApp test) {
            engineCaseId = test.engineCaseId();
            status = test.status();
            servicesJson = test.servicesJson();
            applicationId = test.applicationId();
        } else {
            return Response.status(500).build();
        }

        if (!"RUNNING".equals(status) && !"DEGRADED".equals(status)) {
            return Response.status(409).entity(Map.of("error", "Application status must be RUNNING or DEGRADED")).build();
        }

        if (engineCaseId == null) {
            return Response.status(409).entity(Map.of("error", "No active case for application")).build();
        }

        List<ServiceDefinition> services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        ServiceDefinition target = services.stream()
                .filter(sd -> sd.serviceId().equals(serviceId))
                .findFirst().orElse(null);

        if (target == null) {
            return Response.status(404).entity(Map.of("error", "Service not found: " + serviceId)).build();
        }

        Duration maxCooldown = maxCooldownForService(target);
        if (maxCooldown != null && cooldownChecker.test(applicationId, serviceId)) {
            return Response.status(429).entity(Map.of("error", "Service is cooling down")).build();
        }

        ScalingPolicy policy = target.scalingRules().isEmpty()
                ? ScalingPolicy.UNBOUNDED
                : mergedPolicy(target.scalingRules());

        var event = new ScalingRequestedEvent(
                engineCaseId, applicationId, "",
                serviceId, request.targetReplicas(), target.replicas(),
                request.reason() != null ? request.reason() : "manual",
                policy);

        eventSink.accept(event);
        timestampRecorder.accept(applicationId, serviceId);

        var responseBuilder = Response.accepted();
        if (!target.scalingRules().isEmpty()) {
            responseBuilder.header("X-Scaling-Warning", "active-rules");
            responseBuilder.entity(Map.of(
                    "status", "accepted",
                    "warning", "This service has automatic scaling rules — manual scaling will be overridden when situations change."));
        } else {
            responseBuilder.entity(Map.of("status", "accepted"));
        }
        return responseBuilder.build();
    }

    private Duration maxCooldownForService(ServiceDefinition sd) {
        Duration max = null;
        for (ScalingRule rule : sd.scalingRules()) {
            if (rule.cooldownPeriod() != null) {
                max = max == null ? rule.cooldownPeriod()
                        : rule.cooldownPeriod().compareTo(max) > 0 ? rule.cooldownPeriod() : max;
            }
        }
        return max;
    }

    private ScalingPolicy mergedPolicy(List<ScalingRule> rules) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        Duration cooldown = null;
        for (ScalingRule r : rules) {
            min = Math.min(min, r.minReplicas());
            max = Math.max(max, r.maxReplicas());
            if (r.cooldownPeriod() != null) {
                cooldown = cooldown == null ? r.cooldownPeriod()
                        : r.cooldownPeriod().compareTo(cooldown) > 0 ? r.cooldownPeriod() : cooldown;
            }
        }
        return new ScalingPolicy(min, max, cooldown);
    }
}
```

**Note:** The test double approach using `Object appRecord` with instanceof is a temporary pattern for unit testing without CDI. The production path loads from `ApplicationEntity.findById()`. This will be refined during implementation if a cleaner test seam emerges.

- [ ] **Step 4: Run ScalingResourceTest — expect PASS**

Run: `mvn --batch-mode -o test -pl app -Dtest=ScalingResourceTest`
Expected: all tests pass

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(#56): ScalingResource REST endpoint for manual scaling"
```

---

### Task 5: Blackboard unification + registration wiring + existing code changes

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/case_/ApplicationCaseDescriptor.java:35-36`
- Modify: `app/src/main/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptor.java:87`
- Modify: `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java` (deploy + decommission)
- Modify: `app/src/test/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptorTest.java`
- Test: existing tests updated

**Interfaces:**
- Consumes: `SituationScalingEvaluator.register()` from Task 2
- Consumes: `SituationScalingEvaluator.deregister()` from Task 2
- Consumes: `DriftSignalBridge.registerApplication()` from existing code

- [ ] **Step 1: Unify blackboard path in ApplicationCaseDescriptor**

Use `ide_replace_member` on `ApplicationCaseDescriptor.bindings()` to change:

```java
childCaseBinding("on-scaling-required", ".scalingRequired",
        "ops", "scaling-event", "1.0", ".scalingRequired"),
```

(Change `.scalingSpec` to `.scalingRequired` on the input mapping — 6th arg)

- [ ] **Step 2: Update ScalingEventCaseDescriptor error message**

Use `ide_replace_member` on `ScalingEventCaseDescriptor.evaluateScaling()` to change the null-input error message from:

```java
return WorkerResult.failed("Scaling spec is null — .scalingSpec missing from parent blackboard");
```

to:

```java
return WorkerResult.failed("Scaling spec is null — .scalingRequired missing from parent blackboard");
```

- [ ] **Step 3: Remove cooldown check from evaluateScaling**

The child case should no longer check cooldown — it's enforced at the evaluator/resource level. Remove the `lastScalingTimestamp` + `isCoolingDown` block from `evaluateScaling()` (lines 109-113). The `buildPolicy` method stays — it's still used for clamping.

- [ ] **Step 4: Wire registration in deploy()**

Add `SituationScalingEvaluator` and `DriftSignalBridge` as injected fields on `ApplicationLifecycleService`. In `deploy()`, after the reconciliation loop starts:

```java
// Register for scaling evaluation
Map<String, Integer> baseReplicas = new java.util.HashMap<>();
for (ServiceDefinition sd : services) {
    baseReplicas.put(sd.serviceId(), sd.replicas());
}
evaluator.register(tenancyId, /* appCaseId */ null /* see note */, applicationId.toString(), baseReplicas);

// Register for drift signaling
for (ClusterReferenceEntity cluster : clusters) {
    String key = tenancyId + ":" + applicationId + ":" + cluster.id;
    driftSignalBridge.registerApplication(key, /* appCaseId */ null, applicationId.toString(), cluster.id.toString());
}
```

**Note:** `appCaseId` needs to be set. Currently `deploy()` doesn't start a case — the case is started elsewhere. The `engineCaseId` on `ApplicationEntity` is the field. If it's set during deploy, use `app.engineCaseId`. If not, registration happens after case creation. Check the actual flow during implementation.

- [ ] **Step 5: Wire deregistration in decommission()**

In `decommission()`:

```java
evaluator.deregister(tenancyId, applicationId.toString());
for (ClusterReferenceEntity cluster : clusters) {
    String key = tenancyId + ":" + applicationId + ":" + cluster.id;
    driftSignalBridge.deregisterApplication(key);
}
```

- [ ] **Step 6: Update ScalingEventCaseDescriptorTest**

Update `evaluateCoolingDownRejectsScaling` test — this behavior has moved to the evaluator. Either remove the test or change it to verify that cooldown fields are passed through but no longer block execution at this level.

Add a test verifying the binding uses `.scalingRequired` for input mapping:

```java
@Test
void scalingRequiredBindingUsesUnifiedPath() {
    // Verify via ApplicationCaseDescriptor that the input mapping is .scalingRequired
    var def = ApplicationCaseDescriptor.build();
    var binding = def.getBindings().stream()
            .filter(b -> b.getName().equals("on-scaling-required"))
            .findFirst().orElseThrow();
    assertThat(binding.getSubCase().getInputMapping()).isEqualTo(".scalingRequired");
}
```

- [ ] **Step 7: Run full app test suite**

Run: `mvn --batch-mode -o test -pl app`
Expected: all tests pass

- [ ] **Step 8: Run full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS, all 249+ tests pass

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat(#56): unify blackboard path, wire registration in deploy/decommission"
```

---

### Task 6: Verification and cleanup

- [ ] **Step 1: Run full build from clean**

Run: `mvn --batch-mode clean install`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify test coverage**

Run: `mvn --batch-mode -o test -pl app`

Check that all new test classes pass:
- `ScalingRuleTest`
- `SituationScalingEvaluatorTest`
- `ScalingSignalBridgeTest`
- `ScalingResourceTest`

And all modified test classes still pass:
- `ServiceDefinitionTest`
- `ScalingEventCaseDescriptorTest`
- `ScalingPolicyTest`
- `UpdateServiceReplicasTest`
- `ApplicationGoalCompilerTest`
- `DriftSignalBridgeTest`

- [ ] **Step 3: Run ide_diagnostics on all new and modified files**

Verify no compiler errors or warnings in:
- `ScalingRule.java`
- `CaseSignaler.java`
- `ScalingRequestedEvent.java`
- `ScalingEvaluatorSupport.java`
- `SituationScalingEvaluator.java`
- `ScalingSignalBridge.java`
- `ScalingResource.java`
- `ServiceDefinition.java`
- `ApplicationCaseDescriptor.java`
- `ScalingEventCaseDescriptor.java`
- `ApplicationLifecycleService.java`
- `DriftSignalBridge.java`

- [ ] **Step 4: Commit final state**

If any cleanup was needed:
```bash
git add -A && git commit -m "chore(#56): verification cleanup"
```
