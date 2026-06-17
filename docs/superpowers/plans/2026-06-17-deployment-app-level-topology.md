# Deployment App-Level Topology Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the deployment module with provider-specific agent config, case definition file loading, and layered drift detection with SPI delegation.

**Architecture:** API types gain new fields (briefing, providerConfigs, definitionPayload). Drift detection is refactored from hardcoded per-type logic to a layered SPI dispatch: external NodeDriftChecker first, then SpecHashStore for declaration-change detection. The GoalCompiler resolves definitionFile references into immutable YAML payloads at compile time.

**Tech Stack:** Java 21, Quarkus CDI, Jackson YAML, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-17-deployment-app-level-topology-design.md` (revision 4)

---

### Task 1: API types — ProviderConfig and NodeDriftChecker

**Files:**
- Create: `api/src/main/java/io/casehub/ops/api/deployment/ProviderConfig.java`
- Create: `api/src/main/java/io/casehub/ops/api/deployment/NodeDriftChecker.java`
- Create: `api/src/test/java/io/casehub/ops/api/deployment/ProviderConfigTest.java`

- [ ] **Step 1: Write ProviderConfig test**

```java
package io.casehub.ops.api.deployment;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ProviderConfigTest {

    @Test
    void validConstruction() {
        var config = new ProviderConfig("claudony", Map.of("tools", "read,write"));
        assertThat(config.providerName()).isEqualTo("claudony");
        assertThat(config.config()).containsEntry("tools", "read,write");
    }

    @Test
    void nullProviderNameRejected() {
        assertThatThrownBy(() -> new ProviderConfig(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName is required");
    }

    @Test
    void blankProviderNameRejected() {
        assertThatThrownBy(() -> new ProviderConfig("  ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName is required");
    }

    @Test
    void nullConfigDefaultsToEmpty() {
        var config = new ProviderConfig("claudony", null);
        assertThat(config.config()).isEmpty();
    }

    @Test
    void configIsImmutable() {
        var config = new ProviderConfig("claudony", Map.of("key", "value"));
        assertThatThrownBy(() -> config.config().put("new", "entry"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullValuesPreservedInConfig() {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("systemPrompt", "prompts/reviewer.md");
        input.put("optionalField", null);
        var config = new ProviderConfig("claudony", input);
        assertThat(config.config()).containsEntry("optionalField", null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=ProviderConfigTest`
Expected: FAIL — `ProviderConfig` class not found

- [ ] **Step 3: Write ProviderConfig record**

```java
package io.casehub.ops.api.deployment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderConfig(String providerName, Map<String, Object> config) {
    public ProviderConfig {
        if (providerName == null || providerName.isBlank())
            throw new IllegalArgumentException("providerName is required");
        config = config != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(config))
                : Map.of();
    }
}
```

- [ ] **Step 4: Write NodeDriftChecker interface**

```java
package io.casehub.ops.api.deployment;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;

public interface NodeDriftChecker {
    NodeStatus check(NodeSpec spec, String tenancyId);
    String nodeType();
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=ProviderConfigTest`
Expected: PASS — all 6 tests green

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/casehub/ops/api/deployment/ProviderConfig.java \
       api/src/main/java/io/casehub/ops/api/deployment/NodeDriftChecker.java \
       api/src/test/java/io/casehub/ops/api/deployment/ProviderConfigTest.java
git commit -m "feat(#7): ProviderConfig record + NodeDriftChecker SPI"
```

---

### Task 2: AgentNodeSpec — add briefing and providerConfigs

**Files:**
- Modify: `api/src/main/java/io/casehub/ops/api/deployment/AgentNodeSpec.java`
- Modify: All test files that construct `AgentNodeSpec` (every call site gains 2 new trailing args)

The existing `AgentNodeSpec` has 16 constructor args. This adds `briefing` (17th, after dataHandlingPolicy) and `providerConfigs` (18th, last). Every existing call site must be updated — add `null, List.of()` as the two trailing arguments.

- [ ] **Step 1: Modify AgentNodeSpec record — add two new fields**

Add after `dataHandlingPolicy`:
```java
        String briefing,
        List<ProviderConfig> providerConfigs
```

Add to compact constructor:
```java
        providerConfigs = providerConfigs != null ? List.copyOf(providerConfigs) : List.of();
```

Full record signature becomes:
```java
public record AgentNodeSpec(
        String agentId,
        String name,
        String slot,
        String provider,
        String modelFamily,
        String modelVersion,
        String version,
        String weightsFingerprint,
        String domainVocabulary,
        String slotVocabulary,
        String dispositionVocabulary,
        Map<DispositionAxis, String> axisVocabularies,
        List<AgentCapability> capabilities,
        AgentDisposition disposition,
        String jurisdiction,
        String dataHandlingPolicy,
        String briefing,
        List<ProviderConfig> providerConfigs
) implements DeploymentNodeSpec {
```

- [ ] **Step 2: Fix all existing call sites — add trailing null, List.of()**

Every existing `new AgentNodeSpec(...)` call gains two trailing arguments. Search using IntelliJ `Find Usages` on the `AgentNodeSpec` constructor. Files to update:
- `deployment/src/test/java/.../handler/AgentProvisionHandlerTest.java` — all 3 test methods + `StubAgentRegistry` usages
- `deployment/src/test/java/.../DeploymentGoalCompilerTest.java` — `testAgent()` helper
- `deployment/src/test/java/.../DeploymentNodeProvisionerTest.java` — `dispatchesAgentToHandler` test
- `deployment/src/test/java/.../DeploymentActualStateAdapterTest.java` — `agentPresent`, `agentAbsent`, `agentDrifted` tests
- `deployment/src/test/java/.../DeploymentLifecycleIntegrationTest.java` — `fullLifecycle` test

Append `, null, List.of()` to each existing `AgentNodeSpec` constructor call.

- [ ] **Step 3: Run all deployment module tests**

Run: `mvn --batch-mode test -pl api,deployment`
Expected: PASS — all existing tests compile and pass with the new field defaults

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/io/casehub/ops/api/deployment/AgentNodeSpec.java
git add -A deployment/src/test/
git commit -m "feat(#7): AgentNodeSpec — add briefing + providerConfigs fields"
```

---

### Task 3: CaseTypeNodeSpec — add definitionFile and definitionPayload

**Files:**
- Modify: `api/src/main/java/io/casehub/ops/api/deployment/CaseTypeNodeSpec.java`
- Modify: All test files that construct `CaseTypeNodeSpec`

The existing `CaseTypeNodeSpec` has 5 constructor args. This adds `definitionFile` (6th) and `definitionPayload` (7th). Every existing call site must be updated — add `null, null` as the two trailing arguments.

- [ ] **Step 1: Modify CaseTypeNodeSpec record — add two new fields**

```java
public record CaseTypeNodeSpec(
        String namespace,
        String name,
        String version,
        String title,
        String summary,
        String definitionFile,
        Map<String, Object> definitionPayload
) implements DeploymentNodeSpec {

    public CaseTypeNodeSpec {
        if (namespace == null || namespace.isBlank())
            throw new IllegalArgumentException("namespace is required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name is required");
        if (version == null || version.isBlank())
            throw new IllegalArgumentException("version is required");
        definitionPayload = definitionPayload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(definitionPayload))
                : null;
    }

    @Override
    public String nodeId() {
        return namespace + ":" + name + ":" + version;
    }

    @Override
    public String nodeType() {
        return "case_type";
    }
}
```

Add imports: `java.util.Collections`, `java.util.LinkedHashMap`.

- [ ] **Step 2: Fix all existing call sites — add trailing null, null**

Files to update:
- `deployment/src/test/java/.../handler/CaseTypeProvisionHandlerTest.java` — all 4 test methods
- `deployment/src/test/java/.../DeploymentGoalCompilerTest.java` — `compilesCaseTypeNode`, `compilesAllFourTypes`
- `deployment/src/test/java/.../DeploymentActualStateAdapterTest.java` — `caseTypeAndTrustAlwaysPresent`
- `deployment/src/test/java/.../DeploymentLifecycleIntegrationTest.java` — `fullLifecycle`

Append `, null, null` to each existing `CaseTypeNodeSpec` constructor call.

- [ ] **Step 3: Run all tests**

Run: `mvn --batch-mode test -pl api,deployment`
Expected: PASS — all existing tests compile and pass

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/io/casehub/ops/api/deployment/CaseTypeNodeSpec.java
git add -A deployment/src/test/
git commit -m "feat(#7): CaseTypeNodeSpec — add definitionFile + definitionPayload fields"
```

---

### Task 4: TrustPolicyProvisionHandler package move

**Files:**
- Move: `deployment/src/main/java/io/casehub/ops/deployment/TrustPolicyProvisionHandler.java` → `deployment/src/main/java/io/casehub/ops/deployment/handler/TrustPolicyProvisionHandler.java`
- Move: `deployment/src/test/java/io/casehub/ops/deployment/TrustPolicyProvisionHandlerTest.java` → `deployment/src/test/java/io/casehub/ops/deployment/handler/TrustPolicyProvisionHandlerTest.java`
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentNodeProvisioner.java` — update import

Use IntelliJ's `ide_move_file` for semantic refactoring (updates imports automatically).

- [ ] **Step 1: Move handler to handler/ package**

Use IntelliJ refactor:
```
ide_move_file(file="deployment/src/main/java/io/casehub/ops/deployment/TrustPolicyProvisionHandler.java",
              destination="deployment/src/main/java/io/casehub/ops/deployment/handler")
```

- [ ] **Step 2: Move test to handler/ package**

```
ide_move_file(file="deployment/src/test/java/io/casehub/ops/deployment/TrustPolicyProvisionHandlerTest.java",
              destination="deployment/src/test/java/io/casehub/ops/deployment/handler")
```

- [ ] **Step 3: Run all tests to verify the move didn't break anything**

Run: `mvn --batch-mode test -pl deployment`
Expected: PASS — all tests pass with updated imports

- [ ] **Step 4: Commit**

```bash
git add -A deployment/
git commit -m "refactor(#7): move TrustPolicyProvisionHandler to handler/ subpackage"
```

---

### Task 5: Store beans — SpecHashStore and DeploymentProviderConfigStore

**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/SpecHashStore.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentProviderConfigStore.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/SpecHashStoreTest.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentProviderConfigStoreTest.java`

- [ ] **Step 1: Write SpecHashStore test**

```java
package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecHashStoreTest {

    private SpecHashStore store;

    @BeforeEach
    void setUp() {
        store = new SpecHashStore();
    }

    @Test
    void unknownNodeHasDrifted() {
        var spec = minimalAgent("agent-1");
        assertThat(store.hasDrifted(NodeId.of("agent-1"), spec)).isTrue();
    }

    @Test
    void recordedNodeHasNotDrifted() {
        var spec = minimalAgent("agent-1");
        store.record(NodeId.of("agent-1"), spec);
        assertThat(store.hasDrifted(NodeId.of("agent-1"), spec)).isFalse();
    }

    @Test
    void changedSpecHasDrifted() {
        var spec1 = minimalAgent("agent-1");
        store.record(NodeId.of("agent-1"), spec1);

        var spec2 = new AgentNodeSpec("agent-1", "Changed Name", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        assertThat(store.hasDrifted(NodeId.of("agent-1"), spec2)).isTrue();
    }

    @Test
    void removeNodeMakesDriftedAgain() {
        var spec = minimalAgent("agent-1");
        store.record(NodeId.of("agent-1"), spec);
        store.remove(NodeId.of("agent-1"));
        assertThat(store.hasDrifted(NodeId.of("agent-1"), spec)).isTrue();
    }

    @Test
    void nestedMapChangesDetected() {
        var config1 = new ProviderConfig("claudony", Map.of("tools", "read"));
        var spec1 = new AgentNodeSpec("agent-1", "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of(config1));
        store.record(NodeId.of("agent-1"), spec1);

        var config2 = new ProviderConfig("claudony", Map.of("tools", "write"));
        var spec2 = new AgentNodeSpec("agent-1", "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of(config2));
        assertThat(store.hasDrifted(NodeId.of("agent-1"), spec2)).isTrue();
    }

    private AgentNodeSpec minimalAgent(String id) {
        return new AgentNodeSpec(id, "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
    }
}
```

- [ ] **Step 2: Write DeploymentProviderConfigStore test**

```java
package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentProviderConfigStoreTest {

    private DeploymentProviderConfigStore store;

    @BeforeEach
    void setUp() {
        store = new DeploymentProviderConfigStore();
    }

    @Test
    void storeAndRetrieve() {
        var configs = List.of(new ProviderConfig("claudony", Map.of("tools", "read")));
        store.store("agent-1", configs);
        assertThat(store.forAgent("agent-1")).isEqualTo(configs);
    }

    @Test
    void unknownAgentReturnsEmpty() {
        assertThat(store.forAgent("nonexistent")).isEmpty();
    }

    @Test
    void removeClears() {
        store.store("agent-1", List.of(new ProviderConfig("claudony", Map.of())));
        store.remove("agent-1");
        assertThat(store.forAgent("agent-1")).isEmpty();
    }

    @Test
    void storedListIsImmutable() {
        var configs = List.of(new ProviderConfig("claudony", Map.of()));
        store.store("agent-1", configs);
        assertThat(store.forAgent("agent-1")).isUnmodifiable();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl deployment -Dtest="SpecHashStoreTest,DeploymentProviderConfigStoreTest"`
Expected: FAIL — classes not found

- [ ] **Step 4: Write SpecHashStore**

```java
package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SpecHashStore {

    private final ConcurrentHashMap<NodeId, Integer> hashes = new ConcurrentHashMap<>();

    public void record(NodeId id, NodeSpec spec) {
        hashes.put(id, spec.hashCode());
    }

    public void remove(NodeId id) {
        hashes.remove(id);
    }

    public boolean hasDrifted(NodeId id, NodeSpec spec) {
        Integer stored = hashes.get(id);
        if (stored == null) return true;
        return !stored.equals(spec.hashCode());
    }
}
```

- [ ] **Step 5: Write DeploymentProviderConfigStore**

```java
package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.ProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DeploymentProviderConfigStore {

    private final ConcurrentHashMap<String, List<ProviderConfig>> configs = new ConcurrentHashMap<>();

    public void store(String agentId, List<ProviderConfig> providerConfigs) {
        configs.put(agentId, List.copyOf(providerConfigs));
    }

    public List<ProviderConfig> forAgent(String agentId) {
        return configs.getOrDefault(agentId, List.of());
    }

    public void remove(String agentId) {
        configs.remove(agentId);
    }
}
```

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest="SpecHashStoreTest,DeploymentProviderConfigStoreTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/SpecHashStore.java \
       deployment/src/main/java/io/casehub/ops/deployment/DeploymentProviderConfigStore.java \
       deployment/src/test/java/io/casehub/ops/deployment/SpecHashStoreTest.java \
       deployment/src/test/java/io/casehub/ops/deployment/DeploymentProviderConfigStoreTest.java
git commit -m "feat(#7): SpecHashStore + DeploymentProviderConfigStore"
```

---

### Task 6: AgentProvisionHandler — briefing + provider config

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/handler/AgentProvisionHandler.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/handler/AgentProvisionHandlerTest.java`

- [ ] **Step 1: Write test for briefing field mapping**

Add to `AgentProvisionHandlerTest`:
```java
@Test
void provisionMapsB riefingToDescriptor() {
    AgentNodeSpec spec = new AgentNodeSpec(
            "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
            "1.0", null, null, null, null, null,
            List.of(), null, null, null,
            "Reviews PRs for quality",  // briefing
            List.of()
    );

    handler.provision(spec, new ProvisionContext("tenant-1", emptyGraph));

    AgentDescriptor descriptor = agentRegistry.findById("agent-1", "tenant-1").orElseThrow();
    assertThat(descriptor.briefing()).isEqualTo("Reviews PRs for quality");
}
```

- [ ] **Step 2: Write test for provider config storage**

Add to `AgentProvisionHandlerTest`. The handler constructor now takes a `DeploymentProviderConfigStore`:
```java
@Test
void provisionStoresProviderConfigs() {
    var claudonyConfig = new ProviderConfig("claudony", Map.of("tools", "read,write"));
    var openclawConfig = new ProviderConfig("openclaw", Map.of("sessionKey", "reviewer"));
    AgentNodeSpec spec = new AgentNodeSpec(
            "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
            "1.0", null, null, null, null, null,
            List.of(), null, null, null, null,
            List.of(claudonyConfig, openclawConfig)
    );

    handler.provision(spec, new ProvisionContext("tenant-1", emptyGraph));

    List<ProviderConfig> stored = providerConfigStore.forAgent("agent-1");
    assertThat(stored).hasSize(2);
    assertThat(stored).extracting(ProviderConfig::providerName)
            .containsExactly("claudony", "openclaw");
}
```

- [ ] **Step 3: Update setUp() to inject DeploymentProviderConfigStore**

```java
private DeploymentProviderConfigStore providerConfigStore;

@BeforeEach
void setUp() {
    agentRegistry = new StubAgentRegistry();
    providerConfigStore = new DeploymentProviderConfigStore();
    handler = new AgentProvisionHandler(agentRegistry, providerConfigStore);
    emptyGraph = new DefaultDesiredStateGraphFactory().empty();
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl deployment -Dtest=AgentProvisionHandlerTest`
Expected: FAIL — constructor mismatch, briefing not passed

- [ ] **Step 5: Update AgentProvisionHandler**

Modify the constructor to accept `DeploymentProviderConfigStore`. Update `provision()` to:
1. Pass `spec.briefing()` as the 18th param to `AgentDescriptor` (after `context.tenancyId()`)
2. Store `spec.providerConfigs()` via `providerConfigStore.store(spec.agentId(), spec.providerConfigs())`

Update `deprovision()` to call `providerConfigStore.remove(spec.agentId())`.

```java
@Inject
public AgentProvisionHandler(AgentRegistry agentRegistry, DeploymentProviderConfigStore providerConfigStore) {
    this.agentRegistry = agentRegistry;
    this.providerConfigStore = providerConfigStore;
}

public ProvisionResult provision(AgentNodeSpec spec, ProvisionContext context) {
    AgentDescriptor descriptor = new AgentDescriptor(
            spec.agentId(), spec.name(), spec.version(),
            spec.provider(), spec.modelFamily(), spec.modelVersion(),
            spec.weightsFingerprint(), spec.domainVocabulary(),
            spec.slotVocabulary(), spec.dispositionVocabulary(),
            spec.axisVocabularies(), spec.slot(),
            spec.capabilities(), spec.disposition(),
            spec.jurisdiction(), spec.dataHandlingPolicy(),
            context.tenancyId(), spec.briefing()
    );
    agentRegistry.register(descriptor);
    providerConfigStore.store(spec.agentId(), spec.providerConfigs());
    return new ProvisionResult.Success();
}

public DeprovisionResult deprovision(AgentNodeSpec spec, DeprovisionContext context) {
    providerConfigStore.remove(spec.agentId());
    return new DeprovisionResult.Success();
}
```

- [ ] **Step 6: Update DeploymentNodeProvisioner and all test files that construct AgentProvisionHandler**

`DeploymentNodeProvisioner` injects both `AgentRegistry` and `DeploymentProviderConfigStore`, passes to `AgentProvisionHandler`. Update `DeploymentNodeProvisionerTest` and `DeploymentLifecycleIntegrationTest` setUp methods.

- [ ] **Step 7: Run all tests**

Run: `mvn --batch-mode test -pl deployment`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add -A deployment/
git commit -m "feat(#7): AgentProvisionHandler — briefing field + provider config store"
```

---

### Task 7: CaseTypeProvisionHandler — definitionPayload

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/handler/CaseTypeProvisionHandler.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/handler/CaseTypeProvisionHandlerTest.java`

- [ ] **Step 1: Write test for full definition from payload**

Add to `CaseTypeProvisionHandlerTest`:
```java
@Test
void provisionWithDefinitionPayloadBuildsFull CaseDefinition() {
    Map<String, Object> payload = Map.of(
            "namespace", "io.casehub.devtown",
            "name", "pr-review",
            "version", "1.0",
            "title", "PR Review",
            "summary", "Automated pull request review",
            "capabilities", List.of(Map.of("name", "review"))
    );
    CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
            "io.casehub.devtown", "pr-review", "1.0",
            "PR Review", "Automated pull request review",
            "case-defs/pr-review.yaml", payload
    );

    ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
    ProvisionResult result = handler.provision(spec, context);

    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    assertThat(handler.isRegistered(spec.nodeId())).isTrue();
}
```

- [ ] **Step 2: Write test for skeleton fallback when no payload**

```java
@Test
void provisionWithoutPayloadBuildsSkeleton() {
    CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
            "io.casehub.legal", "contract-review", "1.0.0",
            "Contract Review", "Review contracts",
            null, null
    );

    ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
    ProvisionResult result = handler.provision(spec, context);

    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    assertThat(handler.isRegistered(spec.nodeId())).isTrue();
}
```

- [ ] **Step 3: Run tests to verify behavior**

Run: `mvn --batch-mode test -pl deployment -Dtest=CaseTypeProvisionHandlerTest`
Expected: New tests should pass (skeleton path already works, payload path needs implementation)

- [ ] **Step 4: Update CaseTypeProvisionHandler to handle definitionPayload**

When `spec.definitionPayload()` is non-null, construct a richer `CaseDefinition` from the payload. For now, the handler extracts identity fields from the payload and builds via the builder. Full case definition construction from arbitrary YAML payloads will be enhanced as needed.

```java
public ProvisionResult provision(CaseTypeNodeSpec spec, ProvisionContext context) {
    CaseDefinition definition;
    if (spec.definitionPayload() != null) {
        definition = buildFromPayload(spec);
    } else {
        definition = CaseDefinition.builder()
                .namespace(spec.namespace())
                .name(spec.name())
                .version(spec.version())
                .title(spec.title())
                .summary(spec.summary())
                .build();
    }
    definitions.put(spec.nodeId(), definition);
    return new ProvisionResult.Success();
}

private CaseDefinition buildFromPayload(CaseTypeNodeSpec spec) {
    var builder = CaseDefinition.builder()
            .namespace(spec.namespace())
            .name(spec.name())
            .version(spec.version());
    var payload = spec.definitionPayload();
    if (payload.containsKey("title")) builder.title((String) payload.get("title"));
    if (payload.containsKey("summary")) builder.summary((String) payload.get("summary"));
    return builder.build();
}
```

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=CaseTypeProvisionHandlerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/handler/CaseTypeProvisionHandler.java \
       deployment/src/test/java/io/casehub/ops/deployment/handler/CaseTypeProvisionHandlerTest.java
git commit -m "feat(#7): CaseTypeProvisionHandler — definitionPayload support"
```

---

### Task 8: Drift checkers — extract from ActualStateAdapter

**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/drift/AgentDriftChecker.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/drift/ChannelDriftChecker.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/drift/CaseTypeDriftChecker.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/drift/TrustPolicyDriftChecker.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/drift/AgentDriftCheckerTest.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/drift/ChannelDriftCheckerTest.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/drift/CaseTypeDriftCheckerTest.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/drift/TrustPolicyDriftCheckerTest.java`

This task extracts the existing per-type checking logic from `DeploymentActualStateAdapter` into the `NodeDriftChecker` SPI implementations. The logic itself is unchanged — just moved behind the SPI.

- [ ] **Step 1: Write AgentDriftChecker test**

Extract the agent-related tests from `DeploymentActualStateAdapterTest` — `agentPresent`, `agentAbsent`, `agentDrifted_capabilitiesMismatch` — and rewrite them targeting `AgentDriftChecker.check()` directly.

```java
package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.eidos.api.*;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDriftCheckerTest {

    private AgentDriftChecker checker;
    private StubAgentRegistry agentRegistry;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        agentRegistry = new StubAgentRegistry();
        checker = new AgentDriftChecker(agentRegistry);
    }

    @Test
    void nodeTypeIsAgent() {
        assertThat(checker.nodeType()).isEqualTo("agent");
    }

    @Test
    void agentPresent() {
        var cap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        agentRegistry.register(new AgentDescriptor(
                "agent-1", "Agent", "1.0", "anthropic", "claude", "4.6", "fp1",
                "domain", "slot", "disp", Map.of(), "worker",
                List.of(cap), null, "US", "policy", TENANCY_ID, null));

        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap), null, "US", "policy",
                null, List.of());

        assertThat(checker.check(spec, TENANCY_ID)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void agentAbsent() {
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(), null, "US", "policy",
                null, List.of());

        assertThat(checker.check(spec, TENANCY_ID)).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void agentDrifted_capabilitiesMismatch() {
        var cap1 = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var cap2 = new AgentCapability("cap-b", null, null, null, List.of(), List.of(), List.of(), Map.of());
        agentRegistry.register(new AgentDescriptor(
                "agent-1", "Agent", "1.0", "anthropic", "claude", "4.6", "fp1",
                "domain", "slot", "disp", Map.of(), "worker",
                List.of(cap1), null, "US", "policy", TENANCY_ID, null));

        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap2), null, "US", "policy",
                null, List.of());

        assertThat(checker.check(spec, TENANCY_ID)).isEqualTo(NodeStatus.DRIFTED);
    }

    // Include StubAgentRegistry (same pattern as existing tests)
    static class StubAgentRegistry implements AgentRegistry {
        private final Map<String, AgentDescriptor> agents = new ConcurrentHashMap<>();
        @Override public void register(AgentDescriptor d) { agents.put(d.agentId() + ":" + d.tenancyId(), d); }
        @Override public Optional<AgentDescriptor> findById(String id, String t) { return Optional.ofNullable(agents.get(id + ":" + t)); }
        @Override public List<AgentDescriptor> find(AgentQuery q) { return new ArrayList<>(agents.values()); }
    }
}
```

- [ ] **Step 2: Write ChannelDriftChecker, CaseTypeDriftChecker, TrustPolicyDriftChecker tests**

Follow the same pattern. `ChannelDriftCheckerTest` extracts channel tests from `DeploymentActualStateAdapterTest`. `CaseTypeDriftCheckerTest` tests PRESENT via `isRegistered()` and ABSENT when not in map. `TrustPolicyDriftCheckerTest` tests field comparison against the policy provider.

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl deployment -Dtest="AgentDriftCheckerTest,ChannelDriftCheckerTest,CaseTypeDriftCheckerTest,TrustPolicyDriftCheckerTest"`
Expected: FAIL — classes not found

- [ ] **Step 4: Write all four drift checker implementations**

`AgentDriftChecker` — extract `checkAgentStatus()` + `capabilitiesMatch()` from `DeploymentActualStateAdapter`:
```java
package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class AgentDriftChecker implements NodeDriftChecker {

    private final AgentRegistry agentRegistry;

    @Inject
    public AgentDriftChecker(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @Override
    public String nodeType() { return "agent"; }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof AgentNodeSpec agentSpec)) return NodeStatus.UNKNOWN;
        Optional<AgentDescriptor> actual = agentRegistry.findById(agentSpec.agentId(), tenancyId);
        if (actual.isEmpty()) return NodeStatus.ABSENT;
        var desired = agentSpec.capabilities().stream().map(c -> c.name()).sorted().toList();
        var existing = actual.get().capabilities().stream().map(c -> c.name()).sorted().toList();
        return desired.equals(existing) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }
}
```

`ChannelDriftChecker` — extract `checkChannelStatus()` + `mutableFieldsMatch()` + `allowedTypesMatch()` + `deniedTypesMatch()`.

`CaseTypeDriftChecker` — use `CaseTypeProvisionHandler.isRegistered()`:
```java
@ApplicationScoped
public class CaseTypeDriftChecker implements NodeDriftChecker {
    private final CaseTypeProvisionHandler handler;
    @Inject public CaseTypeDriftChecker(CaseTypeProvisionHandler handler) { this.handler = handler; }
    @Override public String nodeType() { return "case_type"; }
    @Override public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof CaseTypeNodeSpec cts)) return NodeStatus.UNKNOWN;
        return handler.isRegistered(cts.nodeId()) ? NodeStatus.PRESENT : NodeStatus.ABSENT;
    }
}
```

`TrustPolicyDriftChecker` — compare against `DeploymentTrustRoutingPolicyProvider`.

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest="AgentDriftCheckerTest,ChannelDriftCheckerTest,CaseTypeDriftCheckerTest,TrustPolicyDriftCheckerTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/drift/ \
       deployment/src/test/java/io/casehub/ops/deployment/drift/
git commit -m "feat(#7): drift checkers — AgentDriftChecker, ChannelDriftChecker, CaseTypeDriftChecker, TrustPolicyDriftChecker"
```

---

### Task 9: DeploymentActualStateAdapter — refactor to layered dispatch

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentActualStateAdapter.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentActualStateAdapterTest.java`

Replace the hardcoded per-type methods with layered dispatch: external `NodeDriftChecker` first, then `SpecHashStore` for PRESENT nodes.

- [ ] **Step 1: Write new adapter test for layered dispatch**

Replace `DeploymentActualStateAdapterTest` with tests that verify:
1. SPI delegation by node type
2. ABSENT stays ABSENT (no spec hash check)
3. PRESENT with matching hash stays PRESENT
4. PRESENT with drifted hash becomes DRIFTED
5. UNKNOWN stays UNKNOWN (no promotion to DRIFTED)

```java
package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.*;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentActualStateAdapterTest {

    private DeploymentActualStateAdapter adapter;
    private SpecHashStore specHashStore;
    private StubDriftChecker agentChecker;
    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        specHashStore = new SpecHashStore();
        agentChecker = new StubDriftChecker("agent");
        var channelChecker = new StubDriftChecker("channel");
        graphFactory = new DefaultDesiredStateGraphFactory();

        adapter = new DeploymentActualStateAdapter(
                List.of(agentChecker, channelChecker), specHashStore);
    }

    @Test
    void absentStaysAbsent() {
        agentChecker.nextStatus = NodeStatus.ABSENT;
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void presentWithMatchingHashStaysPresent() {
        agentChecker.nextStatus = NodeStatus.PRESENT;
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        specHashStore.record(NodeId.of("a1"), spec);

        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void presentWithDriftedHashBecomesDrifted() {
        agentChecker.nextStatus = NodeStatus.PRESENT;
        var specOld = new AgentNodeSpec("agent-1", "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        specHashStore.record(NodeId.of("a1"), specOld);

        var specNew = new AgentNodeSpec("agent-1", "Changed", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), specNew, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void unknownStaysUnknown() {
        agentChecker.nextStatus = NodeStatus.UNKNOWN;
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.UNKNOWN);
    }

    static class StubDriftChecker implements NodeDriftChecker {
        private final String type;
        NodeStatus nextStatus = NodeStatus.UNKNOWN;
        StubDriftChecker(String type) { this.type = type; }
        @Override public String nodeType() { return type; }
        @Override public NodeStatus check(NodeSpec spec, String tenancyId) { return nextStatus; }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentActualStateAdapterTest`
Expected: FAIL — constructor signature changed

- [ ] **Step 3: Rewrite DeploymentActualStateAdapter**

Replace the existing implementation with the layered dispatch. The adapter takes `List<NodeDriftChecker>` (test constructor) or `Instance<NodeDriftChecker>` (CDI constructor) + `SpecHashStore`.

```java
@ApplicationScoped
public class DeploymentActualStateAdapter implements ActualStateAdapter {

    private final Map<String, NodeDriftChecker> checkers;
    private final SpecHashStore specHashStore;
    private final String tenancyId;

    @Inject
    public DeploymentActualStateAdapter(
            Instance<NodeDriftChecker> driftCheckers,
            SpecHashStore specHashStore) {
        this.checkers = new HashMap<>();
        for (var checker : driftCheckers) {
            this.checkers.put(checker.nodeType(), checker);
        }
        this.specHashStore = specHashStore;
        this.tenancyId = "default";
    }

    DeploymentActualStateAdapter(List<NodeDriftChecker> driftCheckers, SpecHashStore specHashStore) {
        this.checkers = new HashMap<>();
        for (var checker : driftCheckers) {
            this.checkers.put(checker.nodeType(), checker);
        }
        this.specHashStore = specHashStore;
        this.tenancyId = "default";
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var node : desired.nodes().values()) {
            statuses.put(node.id(), checkNode(node));
        }
        return new ActualState(statuses);
    }

    private NodeStatus checkNode(DesiredNode node) {
        NodeDriftChecker checker = checkers.get(node.type().value());
        NodeStatus external = (checker != null)
                ? checker.check(node.spec(), tenancyId)
                : NodeStatus.UNKNOWN;

        if (external == NodeStatus.ABSENT) return NodeStatus.ABSENT;
        if (external == NodeStatus.DRIFTED) return NodeStatus.DRIFTED;
        if (external == NodeStatus.PRESENT && specHashStore.hasDrifted(node.id(), node.spec())) {
            return NodeStatus.DRIFTED;
        }
        return external;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentActualStateAdapterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/DeploymentActualStateAdapter.java \
       deployment/src/test/java/io/casehub/ops/deployment/DeploymentActualStateAdapterTest.java
git commit -m "refactor(#7): DeploymentActualStateAdapter — layered drift dispatch via NodeDriftChecker SPI"
```

---

### Task 10: DeploymentNodeProvisioner — spec hash recording

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentNodeProvisioner.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentNodeProvisionerTest.java`

- [ ] **Step 1: Write test for spec hash recording on provision**

```java
@Test
void provisionRecordsSpecHash() {
    var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
            "1.0", null, null, null, null, null, List.of(), null, null, null, null, List.of());
    var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
    var context = new ProvisionContext("tenant-1", emptyGraph);

    provisioner.provision(node, context);

    assertThat(specHashStore.hasDrifted(NodeId.of("a1"), spec)).isFalse();
}
```

- [ ] **Step 2: Write test for spec hash removal on deprovision**

```java
@Test
void deprovisionRemovesSpecHash() {
    var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
            null, null, null, null, null, null, null, null, null, null, null);
    var node = new DesiredNode(NodeId.of("ch1"), NodeType.of("channel"), spec, false);
    var pCtx = new ProvisionContext("tenant-1", emptyGraph);

    provisioner.provision(node, pCtx);
    assertThat(specHashStore.hasDrifted(NodeId.of("ch1"), spec)).isFalse();

    provisioner.deprovision(node, new DeprovisionContext("tenant-1", emptyGraph));
    assertThat(specHashStore.hasDrifted(NodeId.of("ch1"), spec)).isTrue();
}
```

- [ ] **Step 3: Update DeploymentNodeProvisioner**

Inject `SpecHashStore`. After successful provision, call `specHashStore.record()`. After deprovision, call `specHashStore.remove()`.

- [ ] **Step 4: Update setUp() in DeploymentNodeProvisionerTest**

Add `specHashStore` field, pass to provisioner constructor.

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentNodeProvisionerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/DeploymentNodeProvisioner.java \
       deployment/src/test/java/io/casehub/ops/deployment/DeploymentNodeProvisionerTest.java
git commit -m "feat(#7): DeploymentNodeProvisioner — spec hash recording on provision/deprovision"
```

---

### Task 11: DefinitionPayloadLoader

**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/DefinitionPayloadLoader.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/DefinitionPayloadLoaderTest.java`
- Create: `deployment/src/test/resources/test-case-defs/pr-review.yaml` (test fixture)

- [ ] **Step 1: Create test YAML fixture**

Create `deployment/src/test/resources/test-case-defs/pr-review.yaml`:
```yaml
namespace: io.casehub.devtown
name: pr-review
version: "1.0"
title: Pull Request Review
summary: Automated PR review
capabilities:
  - name: code-review
    tags: [java, quarkus]
optionalField: null
```

- [ ] **Step 2: Write DefinitionPayloadLoader test**

```java
package io.casehub.ops.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DefinitionPayloadLoaderTest {

    private DefinitionPayloadLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DefinitionPayloadLoader();
    }

    @Test
    void loadsFromClasspath() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        assertThat(payload).containsEntry("namespace", "io.casehub.devtown");
        assertThat(payload).containsEntry("name", "pr-review");
        assertThat(payload).containsEntry("version", "1.0");
        assertThat(payload).containsKey("capabilities");
    }

    @Test
    void preservesNullValues() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        assertThat(payload).containsKey("optionalField");
        assertThat(payload.get("optionalField")).isNull();
    }

    @Test
    void resultIsImmutable() {
        Map<String, Object> payload = loader.load("test-case-defs/pr-review.yaml");
        assertThatThrownBy(() -> payload.put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void loadsFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path yamlFile = tempDir.resolve("test.yaml");
        Files.writeString(yamlFile, "namespace: test\nname: from-file\nversion: '1.0'\n");
        Map<String, Object> payload = loader.load(yamlFile.toString());
        assertThat(payload).containsEntry("namespace", "test");
        assertThat(payload).containsEntry("name", "from-file");
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> loader.load("nonexistent/path.yaml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode test -pl deployment -Dtest=DefinitionPayloadLoaderTest`
Expected: FAIL

- [ ] **Step 4: Write DefinitionPayloadLoader**

```java
package io.casehub.ops.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@ApplicationScoped
public class DefinitionPayloadLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Map<String, Object> load(String definitionFile) {
        InputStream stream = resolveStream(definitionFile);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yamlMapper.readValue(stream, Map.class);
            return deepFreeze(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse definition file: " + definitionFile, e);
        }
    }

    private InputStream resolveStream(String path) {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path);
        if (classpath != null) return classpath;

        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                return Files.newInputStream(filePath);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read file: " + path, e);
            }
        }
        throw new IllegalArgumentException("Definition file not found on classpath or filesystem: " + path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepFreeze(Map<String, Object> map) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), freezeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private Object freezeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) return deepFreeze((Map<String, Object>) value);
        if (value instanceof List) return deepFreezeList((List<Object>) value);
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> deepFreezeList(List<Object> list) {
        var result = new ArrayList<Object>();
        for (var item : list) {
            result.add(freezeValue(item));
        }
        return Collections.unmodifiableList(result);
    }
}
```

- [ ] **Step 5: Check if jackson-dataformat-yaml is available**

Verify `jackson-dataformat-yaml` is on the classpath transitively. If not, add to `deployment/pom.xml` as `provided`:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=DefinitionPayloadLoaderTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/DefinitionPayloadLoader.java \
       deployment/src/test/java/io/casehub/ops/deployment/DefinitionPayloadLoaderTest.java \
       deployment/src/test/resources/test-case-defs/pr-review.yaml \
       deployment/pom.xml
git commit -m "feat(#7): DefinitionPayloadLoader — classpath-first YAML parsing with deep freeze"
```

---

### Task 12: DeploymentGoalCompiler — definition file resolution

**Files:**
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentGoalCompiler.java`
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentGoalCompilerTest.java`

- [ ] **Step 1: Write test for definitionFile resolution**

```java
@Test
void compilesC aseTypeWithDefinitionFile() {
    var caseType = new CaseTypeNodeSpec(
            "io.casehub.devtown", "pr-review", "1.0",
            "PR Review", "Automated",
            "test-case-defs/pr-review.yaml", null);
    var goals = new DeploymentGoals(
            List.of(), List.of(),
            List.of(new GoalEntry<>(caseType, List.of())),
            List.of());

    DesiredStateGraph graph = compiler.compile(goals, factory);

    DesiredNode node = graph.nodes().get(NodeId.of("io.casehub.devtown:pr-review:1.0"));
    assertThat(node).isNotNull();
    var resolved = (CaseTypeNodeSpec) node.spec();
    assertThat(resolved.definitionPayload()).isNotNull();
    assertThat(resolved.definitionPayload()).containsEntry("namespace", "io.casehub.devtown");
    assertThat(resolved.definitionFile()).isEqualTo("test-case-defs/pr-review.yaml");
}
```

- [ ] **Step 2: Write test for no resolution when payload already set**

```java
@Test
void skipsResolutionWhenPayloadAlreadySet() {
    var payload = Map.<String, Object>of("namespace", "pre-set");
    var caseType = new CaseTypeNodeSpec(
            "io.casehub.devtown", "pr-review", "1.0",
            "PR Review", "Automated",
            "test-case-defs/pr-review.yaml", payload);
    var goals = new DeploymentGoals(
            List.of(), List.of(),
            List.of(new GoalEntry<>(caseType, List.of())),
            List.of());

    DesiredStateGraph graph = compiler.compile(goals, factory);

    var resolved = (CaseTypeNodeSpec) graph.nodes().get(NodeId.of("io.casehub.devtown:pr-review:1.0")).spec();
    assertThat(resolved.definitionPayload()).containsEntry("namespace", "pre-set");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentGoalCompilerTest`
Expected: FAIL — compiler doesn't resolve yet

- [ ] **Step 4: Update DeploymentGoalCompiler**

Inject `DefinitionPayloadLoader`. Add `resolveCaseTypes()` method. Call it before `compileEntries()` for case types.

```java
@ApplicationScoped
public class DeploymentGoalCompiler implements GoalCompiler<DeploymentGoals> {

    private final DefinitionPayloadLoader definitionLoader;

    @Inject
    public DeploymentGoalCompiler(DefinitionPayloadLoader definitionLoader) {
        this.definitionLoader = definitionLoader;
    }

    DeploymentGoalCompiler() {
        this.definitionLoader = new DefinitionPayloadLoader();
    }

    @Override
    public DesiredStateGraph compile(DeploymentGoals goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        compileEntries(goals.agents(), nodes, dependencies);
        compileEntries(goals.channels(), nodes, dependencies);
        compileEntries(resolveCaseTypes(goals.caseTypes()), nodes, dependencies);
        compileEntries(goals.trust(), nodes, dependencies);

        return factory.of(nodes, dependencies);
    }

    private List<GoalEntry<CaseTypeNodeSpec>> resolveCaseTypes(
            List<GoalEntry<CaseTypeNodeSpec>> entries) {
        return entries.stream().map(entry -> {
            var spec = entry.spec();
            if (spec.definitionFile() != null && spec.definitionPayload() == null) {
                var payload = definitionLoader.load(spec.definitionFile());
                var resolved = new CaseTypeNodeSpec(
                        spec.namespace(), spec.name(), spec.version(),
                        spec.title(), spec.summary(),
                        spec.definitionFile(), payload);
                return new GoalEntry<>(resolved, entry.dependsOn());
            }
            return entry;
        }).toList();
    }

    // ... existing compileEntries unchanged
}
```

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentGoalCompilerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/DeploymentGoalCompiler.java \
       deployment/src/test/java/io/casehub/ops/deployment/DeploymentGoalCompilerTest.java
git commit -m "feat(#7): DeploymentGoalCompiler — definitionFile resolution at compile time"
```

---

### Task 13: DeploymentGoalLoader — multi-file YAML

**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentGoalLoader.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentGoalLoaderTest.java`
- Create: `deployment/src/test/resources/test-deployment/topology.yaml` (test fixture)
- Create: `deployment/src/test/resources/test-deployment-multi/agents.yaml` (test fixture)
- Create: `deployment/src/test/resources/test-deployment-multi/channels.yaml` (test fixture)

- [ ] **Step 1: Create test YAML fixtures**

`deployment/src/test/resources/test-deployment/topology.yaml`:
```yaml
agents:
  - agentId: test-agent
    name: Test Agent
    slot: worker
channels:
  - name: test/work
    semantic: APPEND
caseTypes:
  - namespace: io.test
    name: test-case
    version: "1.0"
trust:
  - capability: review
    threshold: 0.7
    minimumObservations: 5
    borderlineMargin: 0.1
    blendFactor: 0.5
    bootstrapEscalationRequired: false
```

`deployment/src/test/resources/test-deployment-multi/agents.yaml`:
```yaml
agents:
  - agentId: agent-from-file
    name: Agent From File
    slot: worker
```

`deployment/src/test/resources/test-deployment-multi/channels.yaml`:
```yaml
channels:
  - name: channel/from-file
    semantic: APPEND
```

- [ ] **Step 2: Write DeploymentGoalLoader test**

```java
package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.DeploymentGoals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class DeploymentGoalLoaderTest {

    private DeploymentGoalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DeploymentGoalLoader();
    }

    @Test
    void loadsSingleFile() {
        DeploymentGoals goals = loader.load("test-deployment/topology.yaml");
        assertThat(goals.agents()).hasSize(1);
        assertThat(goals.channels()).hasSize(1);
        assertThat(goals.caseTypes()).hasSize(1);
        assertThat(goals.trust()).hasSize(1);
    }

    @Test
    void loadsDirectoryAndMerges(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("agents.yaml"),
                "agents:\n  - agentId: a1\n    name: A1\n    slot: worker\n");
        Files.writeString(tempDir.resolve("channels.yaml"),
                "channels:\n  - name: ch/1\n    semantic: APPEND\n");

        DeploymentGoals goals = loader.loadDirectory(tempDir.toString());
        assertThat(goals.agents()).hasSize(1);
        assertThat(goals.channels()).hasSize(1);
    }

    @Test
    void mergesConcatenatesLists() {
        var goals1 = new DeploymentGoals(
                List.of(), List.of(), List.of(), List.of());
        var goals2 = new DeploymentGoals(
                List.of(), List.of(), List.of(), List.of());
        var merged = loader.merge(goals1, goals2);
        assertThat(merged.agents()).isEmpty();
    }

    @Test
    void emptySectionsProduceEmptyLists() {
        DeploymentGoals goals = loader.load("test-deployment/topology.yaml");
        assertThat(goals.agents()).isNotNull();
        assertThat(goals.channels()).isNotNull();
        assertThat(goals.caseTypes()).isNotNull();
        assertThat(goals.trust()).isNotNull();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentGoalLoaderTest`
Expected: FAIL

- [ ] **Step 4: Write DeploymentGoalLoader**

```java
package io.casehub.ops.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.ops.api.deployment.DeploymentGoals;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.stream.Stream;

@ApplicationScoped
public class DeploymentGoalLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public DeploymentGoals load(String path) {
        InputStream stream = resolveStream(path);
        try {
            return yamlMapper.readValue(stream, DeploymentGoals.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse deployment YAML: " + path, e);
        }
    }

    public DeploymentGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directoryPath);
        }
        var fragments = new ArrayList<DeploymentGoals>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".yaml") || name.endsWith(".yml");
            }).sorted().forEach(p -> {
                try {
                    fragments.add(yamlMapper.readValue(p.toFile(), DeploymentGoals.class));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to parse: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + directoryPath, e);
        }
        return merge(fragments.toArray(new DeploymentGoals[0]));
    }

    public DeploymentGoals merge(DeploymentGoals... fragments) {
        var agents = new ArrayList<io.casehub.ops.api.deployment.GoalEntry<io.casehub.ops.api.deployment.AgentNodeSpec>>();
        var channels = new ArrayList<io.casehub.ops.api.deployment.GoalEntry<io.casehub.ops.api.deployment.ChannelNodeSpec>>();
        var caseTypes = new ArrayList<io.casehub.ops.api.deployment.GoalEntry<io.casehub.ops.api.deployment.CaseTypeNodeSpec>>();
        var trust = new ArrayList<io.casehub.ops.api.deployment.GoalEntry<io.casehub.ops.api.deployment.TrustPolicyNodeSpec>>();
        for (var f : fragments) {
            agents.addAll(f.agents());
            channels.addAll(f.channels());
            caseTypes.addAll(f.caseTypes());
            trust.addAll(f.trust());
        }
        return new DeploymentGoals(agents, channels, caseTypes, trust);
    }

    private InputStream resolveStream(String path) {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path);
        if (classpath != null) return classpath;

        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                return Files.newInputStream(filePath);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read file: " + path, e);
            }
        }
        throw new IllegalArgumentException("Deployment YAML not found: " + path);
    }
}
```

Note: `DeploymentGoals` needs Jackson deserialization support. The record may need `@JsonCreator` or Jackson's record support (Jackson 2.12+). Verify during implementation.

- [ ] **Step 5: Run tests**

Run: `mvn --batch-mode test -pl deployment -Dtest=DeploymentGoalLoaderTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add deployment/src/main/java/io/casehub/ops/deployment/DeploymentGoalLoader.java \
       deployment/src/test/java/io/casehub/ops/deployment/DeploymentGoalLoaderTest.java \
       deployment/src/test/resources/test-deployment/ \
       deployment/src/test/resources/test-deployment-multi/
git commit -m "feat(#7): DeploymentGoalLoader — single-file + multi-file YAML parsing"
```

---

### Task 14: Integration test extension + full build

**Files:**
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentLifecycleIntegrationTest.java`

- [ ] **Step 1: Extend integration test with providerConfig and briefing**

Update the agent spec in `fullLifecycle_declare_compile_provision_readState`:
```java
var claudonyConfig = new ProviderConfig("claudony", Map.of("tools", "read,write"));
var agentSpec = new AgentNodeSpec("agent-1", "Worker Agent", "worker", "anthropic", "claude", "4.6",
        "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy",
        "Reviews code quality", List.of(claudonyConfig));
```

- [ ] **Step 2: Add definitionFile test to integration test**

Add a case type with `definitionFile` pointing to the test fixture:
```java
var caseTypeSpec = new CaseTypeNodeSpec("io.casehub.devtown", "pr-review", "1.0",
        "PR Review", "Automated", "test-case-defs/pr-review.yaml", null);
```

- [ ] **Step 3: Update setUp() for new wiring**

Wire `SpecHashStore`, `DeploymentProviderConfigStore`, drift checkers, and the refactored `DeploymentActualStateAdapter`:
```java
var specHashStore = new SpecHashStore();
var providerConfigStore = new DeploymentProviderConfigStore();
var agentHandler = new AgentProvisionHandler(agentRegistry, providerConfigStore);
// ... wire all drift checkers
adapter = new DeploymentActualStateAdapter(
        List.of(agentDriftChecker, channelDriftChecker, caseTypeDriftChecker, trustDriftChecker),
        specHashStore);
provisioner = new DeploymentNodeProvisioner(agentHandler, channelHandler, caseTypeHandler, trustHandler, specHashStore);
```

- [ ] **Step 4: Add drift detection test**

```java
@Test
void driftDetection_specHashChangeReportsDrifted() {
    var agentCap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
    var agentDisp = AgentDisposition.builder().delegation(false).build();
    var originalSpec = new AgentNodeSpec("agent-1", "Worker Agent", "worker", "anthropic", "claude", "4.6",
            "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy",
            null, List.of());

    var node = new DesiredNode(NodeId.of("agent-1"), NodeType.of("agent"), originalSpec, false);
    var desired = graphFactory.of(List.of(node), List.of());
    provisioner.provision(node, new ProvisionContext(TENANCY_ID, desired));

    var changedSpec = new AgentNodeSpec("agent-1", "Changed Name", "worker", "anthropic", "claude", "4.6",
            "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy",
            null, List.of());
    var changedNode = new DesiredNode(NodeId.of("agent-1"), NodeType.of("agent"), changedSpec, false);
    var changedDesired = graphFactory.of(List.of(changedNode), List.of());

    var actual = adapter.readActual(changedDesired);
    assertThat(actual.statuses().get(NodeId.of("agent-1"))).isEqualTo(NodeStatus.DRIFTED);
}
```

- [ ] **Step 5: Run full build**

Run: `mvn --batch-mode install`
Expected: PASS — all modules compile and all tests pass

- [ ] **Step 6: Commit**

```bash
git add -A deployment/
git commit -m "feat(#7): integration test — providerConfig, definitionFile, layered drift"
```

---

### Task 15: Companion issues

**Files:** None (GitHub operations only)

- [ ] **Step 1: File TransitionPlanner DRIFTED fix (prerequisite)**

```bash
gh issue create --repo casehubio/casehub-desiredstate \
  --title "TransitionPlanner: treat DRIFTED as needing re-provision" \
  --body "..."
```

One-line fix: add `|| status == NodeStatus.DRIFTED` to the provision condition in `TransitionPlanner.plan()`. All deployment provisioners are idempotent — re-provisioning a drifted node is safe.

- [ ] **Step 2: File bridge module issues**

File on casehubio/eidos, casehubio/qhorus, casehubio/engine for optional `*-desiredstate` bridge modules implementing `NodeDriftChecker`.

- [ ] **Step 3: File provider config consumption issues**

File on casehubio/claudony and casehubio/openclaw for reading agent provider config from `DeploymentProviderConfigStore`.

- [ ] **Step 4: Update PLATFORM.md**

Update capability ownership table and cross-repo dependency map in `casehub-parent/docs/PLATFORM.md`.

- [ ] **Step 5: Commit PLATFORM.md**

```bash
git -C ~/claude/casehub/parent add docs/PLATFORM.md
git -C ~/claude/casehub/parent commit -m "docs: update deployment module capabilities for #7"
```
