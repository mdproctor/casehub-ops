# Reverse Index + requiresHuman Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify `requiresHuman` into `NodeSpec.requiresHuman()` with OR composition in `DesiredNode`, fix IoT provisioner bugs, and add a reverse index to `DeploymentProviderConfigStore`.

**Architecture:** Add `default boolean requiresHuman()` to `NodeSpec` (casehub-desiredstate). Override `DesiredNode.requiresHuman()` to OR the record field with `spec.requiresHuman()`. Domain specs override the default. Compilers pass `false` unless there's an author-level override. Reverse index maintained as a second `ConcurrentHashMap` in the store.

**Tech Stack:** Java 21 records, Quarkus CDI, JUnit 5, AssertJ

## Global Constraints

- casehub-desiredstate changes must be committed and `mvn install`'d before ops changes compile
- All tests use JUnit 5 + AssertJ (no Quarkus test runner needed for these changes)
- No new dependencies introduced
- Spec: `../../../public/casehub-ops/specs/issue-27-reverse-index-requires-human/2026-07-01-reverse-index-requires-human-design.md`

---

### Task 1: NodeSpec.requiresHuman() + DesiredNode OR composition (casehub-desiredstate)

**Repo:** `/Users/mdproctor/claude/casehub/desiredstate`

**Files:**
- Modify: `api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java`
- Modify: `api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java`
- Modify: `api/src/test/java/io/casehub/desiredstate/api/CoreTypesTest.java`

**Interfaces:**
- Produces: `NodeSpec.requiresHuman()` default method returning `false`
- Produces: `DesiredNode.requiresHuman()` overridden accessor returning `requiresHuman || spec.requiresHuman()`

- [ ] **Step 1: Write failing tests for NodeSpec.requiresHuman() and DesiredNode OR composition**

Add to `CoreTypesTest.java`:

```java
@Test void nodeSpec_requiresHuman_defaultFalse() {
    NodeSpec spec = new TestSpec("Library", 12);
    assertThat(spec.requiresHuman()).isFalse();
}

record HumanSpec(String name) implements NodeSpec {
    @Override public boolean requiresHuman() { return true; }
}

@Test void desiredNode_orComposition_specTrue_fieldFalse() {
    var spec = new HumanSpec("dragon-lair");
    var node = new DesiredNode(new NodeId("lair"), new NodeType("room"), spec, false);
    assertThat(node.requiresHuman()).isTrue();
}

@Test void desiredNode_orComposition_specFalse_fieldTrue() {
    var spec = new TestSpec("Library", 12);
    var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, true);
    assertThat(node.requiresHuman()).isTrue();
}

@Test void desiredNode_orComposition_bothFalse() {
    var spec = new TestSpec("Library", 12);
    var node = new DesiredNode(new NodeId("library"), new NodeType("room"), spec, false);
    assertThat(node.requiresHuman()).isFalse();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f /Users/mdproctor/claude/casehub/desiredstate/api/pom.xml test -pl . -Dtest=CoreTypesTest -DfailIfNoTests=false --batch-mode`

Expected: `nodeSpec_requiresHuman_defaultFalse` fails (method doesn't exist). `desiredNode_orComposition_specTrue_fieldFalse` fails (no OR composition — returns `false` because field is `false`).

- [ ] **Step 3: Implement NodeSpec.requiresHuman() and DesiredNode OR composition**

`NodeSpec.java` — add default method:
```java
public interface NodeSpec {
    default boolean requiresHuman() { return false; }
}
```

`DesiredNode.java` — override the auto-generated accessor:
```java
@Override
public boolean requiresHuman() {
    return requiresHuman || spec.requiresHuman();
}
```

Inside the method body, `requiresHuman` (unqualified) refers to the record component field. `spec.requiresHuman()` calls the `NodeSpec` default method. JLS §8.10.3: a record can explicitly declare an accessor method, suppressing the auto-generated one.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -f /Users/mdproctor/claude/casehub/desiredstate/api/pom.xml test -pl . -Dtest=CoreTypesTest -DfailIfNoTests=false --batch-mode`

Expected: All `CoreTypesTest` tests pass, including the existing `desiredNode_humanFlag` test (unchanged — `true` field still yields `true`).

- [ ] **Step 5: Run full desiredstate test suite**

Run: `mvn -f /Users/mdproctor/claude/casehub/desiredstate/pom.xml test --batch-mode`

Expected: All tests pass. The existing `SimpleTransitionExecutorTest.requiresHuman_takesPrecedence_overPendingApprovalHandler` test uses `DesiredNode(..., true)` with a `TestSpec` that returns `false` from the default — OR yields `true`, same as before.

- [ ] **Step 6: Install to local Maven repository**

Run: `mvn -f /Users/mdproctor/claude/casehub/desiredstate/pom.xml install -DskipTests --batch-mode`

This publishes the updated `casehub-desiredstate-api` so casehub-ops can compile against it.

- [ ] **Step 7: Commit**

```
git -C /Users/mdproctor/claude/casehub/desiredstate add api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java api/src/test/java/io/casehub/desiredstate/api/CoreTypesTest.java
git -C /Users/mdproctor/claude/casehub/desiredstate commit -m "feat(#28): NodeSpec.requiresHuman() + DesiredNode OR composition"
```

---

### Task 2: requiresHuman unification — spec overrides + compiler/provisioner cleanup (casehub-ops)

**Repo:** `/Users/mdproctor/claude/casehub/ops`

**Files:**
- Modify: `api/src/main/java/io/casehub/ops/api/iot/PhysicalDeviceSpec.java` — override `requiresHuman()` → `true`
- Modify: `api/src/main/java/io/casehub/ops/api/compliance/ComplianceControlSpec.java` — override `requiresHuman()` → `requiresHumanReview()`
- Modify: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceGoalCompiler.java` — `spec.requiresHumanReview()` → `false`
- Modify: `iot/src/main/java/io/casehub/ops/iot/IoTGoalCompiler.java` — hardcoded `true`/`false` → `false`
- Modify: `iot/src/main/java/io/casehub/ops/iot/IoTNodeProvisioner.java` — remove dead provision branch, fix deprovision
- Modify: `iot/src/test/java/io/casehub/ops/iot/IoTNodeProvisionerTest.java` — update provision test, add deprovision test

**Interfaces:**
- Consumes: `NodeSpec.requiresHuman()` from Task 1
- Produces: `PhysicalDeviceSpec.requiresHuman()` → `true`, `ComplianceControlSpec.requiresHuman()` → `requiresHumanReview()`

- [ ] **Step 1: Write failing test for PhysicalDeviceSpec.requiresHuman()**

Add to a new test class `api/src/test/java/io/casehub/ops/api/iot/PhysicalDeviceSpecTest.java`:

```java
package io.casehub.ops.api.iot;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PhysicalDeviceSpecTest {
    @Test
    void requiresHuman_alwaysTrue() {
        var spec = new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label");
        assertThat(spec.requiresHuman()).isTrue();
    }
}
```

- [ ] **Step 2: Write failing test for ComplianceControlSpec.requiresHuman()**

Add to a new test class `api/src/test/java/io/casehub/ops/api/compliance/ComplianceControlSpecTest.java`:

```java
package io.casehub.ops.api.compliance;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceControlSpecTest {
    @Test
    void requiresHuman_delegatesToRequiresHumanReview_true() {
        var spec = new ComplianceControlSpec(
            "access-review", "ACCESS_REVIEW", "Access Review", "Quarterly",
            List.of(), 90, true, Map.of());
        assertThat(spec.requiresHuman()).isTrue();
    }

    @Test
    void requiresHuman_delegatesToRequiresHumanReview_false() {
        var spec = new ComplianceControlSpec(
            "encryption", "ENCRYPTION_AT_REST", "Encryption", "AES-256",
            List.of(), 30, false, Map.of());
        assertThat(spec.requiresHuman()).isFalse();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/api/pom.xml test --batch-mode`

Expected: `PhysicalDeviceSpecTest.requiresHuman_alwaysTrue` fails (method returns `false` from default). `ComplianceControlSpecTest` tests fail (method returns `false` from default).

- [ ] **Step 4: Implement spec overrides**

`PhysicalDeviceSpec.java` — add override:
```java
@Override public boolean requiresHuman() { return true; }
```

`ComplianceControlSpec.java` — add override:
```java
@Override public boolean requiresHuman() { return requiresHumanReview(); }
```

- [ ] **Step 5: Run api tests to verify they pass**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/api/pom.xml test --batch-mode`

Expected: All pass.

- [ ] **Step 6: Simplify IoTGoalCompiler**

Change all `DesiredNode` constructions from hardcoded booleans to `false`:

In the `if (goal.physical())` block:
- `new DesiredNode(..., new PhysicalDeviceSpec(...), true)` → `new DesiredNode(..., new PhysicalDeviceSpec(...), false)`
- The config node already passes `false` — unchanged.

In the `else` block — unchanged (already `false`).

- [ ] **Step 7: Run IoTGoalCompilerTest to verify existing tests still pass**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/iot/pom.xml test -Dtest=IoTGoalCompilerTest --batch-mode`

Expected: All 5 tests pass. The `physicalTrue_createsTwoNodesAndDependency` test asserts `requiresHuman()` is `true` — this still passes because `PhysicalDeviceSpec.requiresHuman()` returns `true` via OR composition, even though the field is now `false`.

- [ ] **Step 8: Simplify ComplianceGoalCompiler**

Change: `spec.requiresHumanReview()` → `false` in the `DesiredNode` constructor call.

```java
nodes.add(new DesiredNode(
    NodeId.of(spec.controlId()),
    NodeType.of(spec.controlType()),
    spec,
    false));
```

- [ ] **Step 9: Run ComplianceGoalCompilerTest to verify existing tests still pass**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/compliance/pom.xml test -Dtest=ComplianceGoalCompilerTest --batch-mode`

Expected: All 5 tests pass. `humanReviewControlSetsRequiresHuman` asserts `requiresHuman()` is `true` — passes via OR composition with `ComplianceControlSpec.requiresHuman()`.

- [ ] **Step 10: Update IoTNodeProvisioner — provision**

In `provision()`, change the type check from:
```java
if (!(node.spec() instanceof PhysicalDeviceSpec || node.spec() instanceof DeviceConfigSpec))
```
to:
```java
if (!(node.spec() instanceof DeviceConfigSpec))
```

In `doProvision()`, remove the `PhysicalDeviceSpec` case:
```java
private ProvisionResult doProvision(DesiredNode node, ProvisionContext context) {
    return switch (node.spec()) {
        case DeviceConfigSpec s -> provisionConfig(s);
        default -> new ProvisionResult.Failed("unknown spec type: " + node.spec().getClass());
    };
}
```

- [ ] **Step 11: Update IoTNodeProvisioner — deprovision**

In `doDeprovision()`, change `PhysicalDeviceSpec` from `Failed` to `Success`:
```java
private DeprovisionResult doDeprovision(DesiredNode node, DeprovisionContext context) {
    return switch (node.spec()) {
        case PhysicalDeviceSpec s -> new DeprovisionResult.Success();
        case DeviceConfigSpec s -> new DeprovisionResult.Success();
        default -> new DeprovisionResult.Failed("unknown spec type");
    };
}
```

- [ ] **Step 12: Update IoTNodeProvisionerTest**

Rename `physicalProvision_returnsFailed` to `physicalProvision_rejectedAsUnknownSpec` — the test still asserts `Failed` but now it's because the type check rejects `PhysicalDeviceSpec`:

```java
@Test
void physicalProvision_rejectedAsUnknownSpec() {
    var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
    var node = new DesiredNode(NodeId.of("dev-1"), NodeType.of("physical-device"),
        new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label"), false);
    var result = provisioner.provision(node, context());

    assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
}
```

Note: field changed from `true` to `false` — `requiresHuman()` still returns `true` via OR, but the field value doesn't matter since the provisioner only checks spec type.

Add physical device deprovision test:

```java
@Test
void physicalDeprovision_returnsSuccess() {
    var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
    var node = new DesiredNode(NodeId.of("dev-1"), NodeType.of("physical-device"),
        new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label"), false);
    var result = provisioner.deprovision(node, deprovisionContext());

    assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
}
```

- [ ] **Step 13: Run full IoT test suite**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/iot/pom.xml test --batch-mode`

Expected: All tests pass.

- [ ] **Step 14: Run full ops test suite**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/pom.xml test --batch-mode`

Expected: All tests pass across all modules (api, deployment, compliance, infra, iot, testing).

- [ ] **Step 15: Commit**

```
git -C /Users/mdproctor/claude/casehub/ops add api/ compliance/ iot/
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#28): requiresHuman unification — spec overrides, compiler simplification, IoT provisioner cleanup"
```

---

### Task 3: Reverse index for declaredAgentIds (casehub-ops deployment)

**Repo:** `/Users/mdproctor/claude/casehub/ops`

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentProviderConfigStore.java`
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentProvisionerConfigRegistry.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentProviderConfigStoreTest.java`

**Interfaces:**
- Produces: `DeploymentProviderConfigStore.agentIdsForProvider(String providerName)` → `Set<String>`

- [ ] **Step 1: Write failing tests for the reverse index**

Add to `DeploymentProviderConfigStoreTest.java`:

```java
@Test
void agentIdsForProvider_returnsMatchingAgents() {
    store.store("agent-1", List.of(
        new ProviderConfig("claudony", Map.of()),
        new ProviderConfig("openclaw", Map.of())));
    store.store("agent-2", List.of(
        new ProviderConfig("claudony", Map.of())));
    store.store("agent-3", List.of(
        new ProviderConfig("openclaw", Map.of())));

    assertThat(store.agentIdsForProvider("claudony"))
        .containsExactlyInAnyOrder("agent-1", "agent-2");
    assertThat(store.agentIdsForProvider("openclaw"))
        .containsExactlyInAnyOrder("agent-1", "agent-3");
}

@Test
void agentIdsForProvider_unknownProviderReturnsEmpty() {
    store.store("agent-1", List.of(new ProviderConfig("claudony", Map.of())));
    assertThat(store.agentIdsForProvider("nonexistent")).isEmpty();
}

@Test
void agentIdsForProvider_reflectsRemoval() {
    store.store("agent-1", List.of(new ProviderConfig("claudony", Map.of())));
    store.store("agent-2", List.of(new ProviderConfig("claudony", Map.of())));
    store.remove("agent-1");
    assertThat(store.agentIdsForProvider("claudony")).containsExactly("agent-2");
}

@Test
void agentIdsForProvider_handlesProviderChange() {
    store.store("agent-1", List.of(new ProviderConfig("claudony", Map.of())));
    assertThat(store.agentIdsForProvider("claudony")).containsExactly("agent-1");
    assertThat(store.agentIdsForProvider("openclaw")).isEmpty();

    store.store("agent-1", List.of(new ProviderConfig("openclaw", Map.of())));
    assertThat(store.agentIdsForProvider("claudony")).isEmpty();
    assertThat(store.agentIdsForProvider("openclaw")).containsExactly("agent-1");
}

@Test
void agentIdsForProvider_isUnmodifiable() {
    store.store("agent-1", List.of(new ProviderConfig("claudony", Map.of())));
    assertThatThrownBy(() -> store.agentIdsForProvider("claudony").add("agent-2"))
        .isInstanceOf(UnsupportedOperationException.class);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/deployment/pom.xml test -Dtest=DeploymentProviderConfigStoreTest --batch-mode`

Expected: 5 new tests fail (method `agentIdsForProvider` doesn't exist).

- [ ] **Step 3: Implement the reverse index**

In `DeploymentProviderConfigStore.java`, add the reverse index field:

```java
private final ConcurrentHashMap<String, Set<String>> providerToAgents = new ConcurrentHashMap<>();
```

Update `store()`:
```java
public void store(String agentId, List<ProviderConfig> providerConfigs) {
    // Remove agent from all previous provider sets
    Map<String, ProviderConfig> previous = configs.get(agentId);
    if (previous != null) {
        for (String oldProvider : previous.keySet()) {
            var set = providerToAgents.get(oldProvider);
            if (set != null) {
                set.remove(agentId);
            }
        }
    }

    var map = new LinkedHashMap<String, ProviderConfig>();
    for (ProviderConfig pc : providerConfigs) {
        if (map.containsKey(pc.providerName())) {
            LOG.warnf("Duplicate providerName '%s' for agent '%s' — last write wins", pc.providerName(), agentId);
        }
        map.put(pc.providerName(), pc);
    }
    configs.put(agentId, Collections.unmodifiableMap(map));

    // Add agent to new provider sets
    for (String providerName : map.keySet()) {
        providerToAgents.computeIfAbsent(providerName, k -> ConcurrentHashMap.newKeySet()).add(agentId);
    }
}
```

Update `remove()`:
```java
public void remove(String agentId) {
    Map<String, ProviderConfig> removed = configs.remove(agentId);
    if (removed != null) {
        for (String providerName : removed.keySet()) {
            var set = providerToAgents.get(providerName);
            if (set != null) {
                set.remove(agentId);
            }
        }
    }
}
```

Add the new method:
```java
public Set<String> agentIdsForProvider(String providerName) {
    var set = providerToAgents.get(providerName);
    return set != null ? Set.copyOf(set) : Set.of();
}
```

- [ ] **Step 4: Run store tests to verify they pass**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/deployment/pom.xml test -Dtest=DeploymentProviderConfigStoreTest --batch-mode`

Expected: All tests pass (existing + 5 new).

- [ ] **Step 5: Update DeploymentProvisionerConfigRegistry to use the reverse index**

In `DeploymentProvisionerConfigRegistry.java`, change `declaredAgentIds`:

```java
@Override
public Set<String> declaredAgentIds(String providerName) {
    return store.agentIdsForProvider(providerName);
}
```

- [ ] **Step 6: Run registry tests to verify existing tests still pass**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/deployment/pom.xml test -Dtest=DeploymentProvisionerConfigRegistryTest --batch-mode`

Expected: All 5 existing tests pass. The `declaredAgentIds*` tests verify behavior, not implementation — same results from the reverse index.

- [ ] **Step 7: Run full deployment test suite**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/deployment/pom.xml test --batch-mode`

Expected: All tests pass.

- [ ] **Step 8: Run full ops test suite**

Run: `mvn -f /Users/mdproctor/claude/casehub/ops/pom.xml test --batch-mode`

Expected: All tests pass across all modules.

- [ ] **Step 9: Commit**

```
git -C /Users/mdproctor/claude/casehub/ops add deployment/
git -C /Users/mdproctor/claude/casehub/ops commit -m "perf(#27): reverse index in DeploymentProviderConfigStore for declaredAgentIds"
```
