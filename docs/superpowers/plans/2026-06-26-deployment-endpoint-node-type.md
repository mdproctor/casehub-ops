# Endpoint Node Type — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `EndpointNodeSpec` as a 5th deployment node type, enabling the deployment YAML to declare and manage `EndpointRegistry` entries (Kafka topics, MCP servers, gRPC services, etc.) with desired-state reconciliation and drift detection.

**Architecture:** Single protocol-agnostic `EndpointNodeSpec` record extending the `DeploymentNodeSpec` sealed hierarchy. Provisioner registers `EndpointDescriptor` via `EndpointRegistry.register()`. Drift checker compares via `toDescriptor()` + record `equals()`. Compilation uses the existing generic `compileEntries()` path.

**Tech Stack:** Java 21 records, Quarkus CDI (`@ApplicationScoped`, `@Inject`), Jackson (`@JsonIgnoreProperties`), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-26-deployment-endpoint-node-type-design.md`

## Global Constraints

- No new Maven dependencies — `casehub-platform-api` already provides all endpoint types
- All tests are plain JUnit + AssertJ — no `@QuarkusTest`
- `Map.copyOf()` for `properties` (String→String, no null values — matches `EndpointDescriptor` contract)
- `Set.copyOf()` for `capabilities`
- `@JsonIgnoreProperties(ignoreUnknown = true)` on `EndpointNodeSpec` (consistency with all other spec records)
- Use `EndpointPropertyKeys.TOPIC` and `EndpointPropertyKeys.URL` constants — never hardcoded strings
- `requiresHuman` is `false` for all endpoint nodes

---

### Task 1: EndpointNodeSpec + API type changes

**Files:**
- Create: `api/src/main/java/io/casehub/ops/api/deployment/EndpointNodeSpec.java`
- Modify: `api/src/main/java/io/casehub/ops/api/deployment/DeploymentNodeSpec.java`
- Modify: `api/src/main/java/io/casehub/ops/api/deployment/DeploymentGoals.java`
- Test: `api/src/test/java/io/casehub/ops/api/deployment/EndpointNodeSpecTest.java`

**Interfaces:**
- Consumes: `EndpointType`, `EndpointProtocol`, `EndpointCapability`, `EndpointPropertyKeys`, `EndpointDescriptor`, `Path` from `casehub-platform-api` (already on classpath)
- Produces: `EndpointNodeSpec` record — consumed by Tasks 2, 3, 4, 5. Key methods: `nodeId()` returns `path`, `nodeType()` returns `"endpoint"`, `toDescriptor(String tenancyId)` returns `EndpointDescriptor`.

- [ ] **Step 1: Write the EndpointNodeSpec test**

Create `api/src/test/java/io/casehub/ops/api/deployment/EndpointNodeSpecTest.java`:

```java
package io.casehub.ops.api.deployment;

import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class EndpointNodeSpecTest {

    @Test
    void validConstruction() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.path()).isEqualTo("streams/vitals");
        assertThat(spec.type()).isEqualTo(EndpointType.SERVICE);
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.KAFKA);
        assertThat(spec.properties()).containsEntry(EndpointPropertyKeys.TOPIC, "patient.vitals");
        assertThat(spec.capabilities()).containsExactly(EndpointCapability.RECEIVE);
        assertThat(spec.credentialRef()).isNull();
    }

    @Test
    void nodeIdReturnsPath() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.nodeId()).isEqualTo("streams/vitals");
    }

    @Test
    void nodeTypeReturnsEndpoint() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.nodeType()).isEqualTo("endpoint");
    }

    @Test
    void blankPathRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "  ", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path is required");
    }

    @Test
    void nullPathRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                null, EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path is required");
    }

    @Test
    void nullTypeRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "test/path", null, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullProtocolRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "test/path", EndpointType.SERVICE, null,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void kafkaRequiresTopic() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(), null, Set.of(EndpointCapability.RECEIVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KAFKA")
                .hasMessageContaining(EndpointPropertyKeys.TOPIC);
    }

    @Test
    void httpRequiresUrl() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/GRPC")
                .hasMessageContaining(EndpointPropertyKeys.URL);
    }

    @Test
    void grpcRequiresUrl() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "services/inference", EndpointType.SERVICE, EndpointProtocol.GRPC,
                Map.of(), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/GRPC");
    }

    @Test
    void amqpPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "queues/events", EndpointType.SERVICE, EndpointProtocol.AMQP,
                Map.of(), null, Set.of(EndpointCapability.RECEIVE));
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.AMQP);
    }

    @Test
    void mcpPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "tools/github", EndpointType.SERVICE, EndpointProtocol.MCP,
                Map.of("serverName", "github"), null, Set.of(EndpointCapability.QUERY));
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.MCP);
    }

    @Test
    void camelPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "routes/ftp-ingest", EndpointType.SERVICE, EndpointProtocol.CAMEL,
                Map.of(), null, Set.of());
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.CAMEL);
    }

    @Test
    void qhorusPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "internal/mesh", EndpointType.SERVICE, EndpointProtocol.QHORUS,
                Map.of(), null, Set.of());
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.QHORUS);
    }

    @Test
    void propertiesAreImmutable() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThatThrownBy(() -> spec.properties().put("new", "entry"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void capabilitiesAreImmutable() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThatThrownBy(() -> spec.capabilities().add(EndpointCapability.SEND))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullPropertiesDefaultsToEmpty() {
        var spec = new EndpointNodeSpec(
                "tools/github", EndpointType.SERVICE, EndpointProtocol.MCP,
                null, null, Set.of());
        assertThat(spec.properties()).isEmpty();
    }

    @Test
    void nullCapabilitiesDefaultsToEmpty() {
        var spec = new EndpointNodeSpec(
                "tools/github", EndpointType.SERVICE, EndpointProtocol.MCP,
                Map.of(), null, null);
        assertThat(spec.capabilities()).isEmpty();
    }

    @Test
    void credentialRefIsNullable() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.credentialRef()).isNull();

        var withCreds = new EndpointNodeSpec(
                "services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), "api-creds",
                Set.of(EndpointCapability.QUERY));
        assertThat(withCreds.credentialRef()).isEqualTo("api-creds");
    }

    @Test
    void toDescriptorMapsAllFields() {
        var spec = new EndpointNodeSpec(
                "streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "patient.vitals"),
                "kafka-creds", Set.of(EndpointCapability.RECEIVE));

        var descriptor = spec.toDescriptor("tenant-1");

        assertThat(descriptor.path()).isEqualTo(Path.parse("streams/vitals"));
        assertThat(descriptor.tenancyId()).isEqualTo("tenant-1");
        assertThat(descriptor.type()).isEqualTo(EndpointType.SERVICE);
        assertThat(descriptor.protocol()).isEqualTo(EndpointProtocol.KAFKA);
        assertThat(descriptor.properties()).containsEntry(EndpointPropertyKeys.TOPIC, "patient.vitals");
        assertThat(descriptor.credentialRef()).isEqualTo("kafka-creds");
        assertThat(descriptor.capabilities()).containsExactly(EndpointCapability.RECEIVE);
    }

    @Test
    void toDescriptorTenancyIdInjected() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        var d1 = spec.toDescriptor("tenant-a");
        var d2 = spec.toDescriptor("tenant-b");
        assertThat(d1.tenancyId()).isEqualTo("tenant-a");
        assertThat(d2.tenancyId()).isEqualTo("tenant-b");
    }

    private EndpointNodeSpec kafkaEndpoint(String path, String topic) {
        return new EndpointNodeSpec(path, EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, topic), null,
                Set.of(EndpointCapability.RECEIVE));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl api test -Dtest=EndpointNodeSpecTest -DfailIfNoTests=false --batch-mode`
Expected: Compilation failure — `EndpointNodeSpec` does not exist yet.

- [ ] **Step 3: Create EndpointNodeSpec record**

Create `api/src/main/java/io/casehub/ops/api/deployment/EndpointNodeSpec.java` with the exact content from the spec (revision 2): `@JsonIgnoreProperties`, `EndpointPropertyKeys` constants for validation, `toDescriptor(String tenancyId)`, `requireProperty()` private helper.

- [ ] **Step 4: Update DeploymentNodeSpec sealed permits**

In `api/src/main/java/io/casehub/ops/api/deployment/DeploymentNodeSpec.java`, add `EndpointNodeSpec` as the 5th permit:

```java
public sealed interface DeploymentNodeSpec extends NodeSpec permits
        AgentNodeSpec, ChannelNodeSpec, CaseTypeNodeSpec, TrustPolicyNodeSpec, EndpointNodeSpec {
```

- [ ] **Step 5: Update DeploymentGoals with endpoints field**

In `api/src/main/java/io/casehub/ops/api/deployment/DeploymentGoals.java`, add the `endpoints` parameter and null-safe copy:

```java
public record DeploymentGoals(
        List<GoalEntry<AgentNodeSpec>> agents,
        List<GoalEntry<ChannelNodeSpec>> channels,
        List<GoalEntry<CaseTypeNodeSpec>> caseTypes,
        List<GoalEntry<TrustPolicyNodeSpec>> trust,
        List<GoalEntry<EndpointNodeSpec>> endpoints
) {
    public DeploymentGoals {
        agents = agents != null ? List.copyOf(agents) : List.of();
        channels = channels != null ? List.copyOf(channels) : List.of();
        caseTypes = caseTypes != null ? List.copyOf(caseTypes) : List.of();
        trust = trust != null ? List.copyOf(trust) : List.of();
        endpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
    }
}
```

- [ ] **Step 6: Run API module tests**

Run: `mvn -pl api test --batch-mode`
Expected: `EndpointNodeSpecTest` passes. `ProviderConfigTest` passes. The deployment module will NOT compile yet (broken exhaustive switches) — that's expected and fixed in Task 2.

- [ ] **Step 7: Commit**

```
feat(#6): EndpointNodeSpec — 5th deployment node type

Adds EndpointNodeSpec record to casehub-ops-api with protocol-aware
validation (EndpointPropertyKeys.TOPIC for KAFKA, URL for HTTP/GRPC),
toDescriptor(tenancyId) conversion, and @JsonIgnoreProperties.
Extends DeploymentNodeSpec sealed permits and DeploymentGoals.
```

---

### Task 2: EndpointProvisionHandler + EndpointDriftChecker + wiring

**Files:**
- Create: `deployment/src/main/java/io/casehub/ops/deployment/handler/EndpointProvisionHandler.java`
- Create: `deployment/src/main/java/io/casehub/ops/deployment/drift/EndpointDriftChecker.java`
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentNodeProvisioner.java` (add switch case + inject handler)
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentGoalCompiler.java` (add `compileEntries` call)
- Modify: `deployment/src/main/java/io/casehub/ops/deployment/DeploymentGoalLoader.java` (add endpoint merge)
- Create: `deployment/src/test/java/io/casehub/ops/deployment/handler/EndpointProvisionHandlerTest.java`
- Create: `deployment/src/test/java/io/casehub/ops/deployment/drift/EndpointDriftCheckerTest.java`
- Modify: All test files with `DeploymentGoals` or `DeploymentNodeProvisioner` constructor calls

**Interfaces:**
- Consumes: `EndpointNodeSpec.toDescriptor(String)` from Task 1. `EndpointRegistry`, `EndpointDescriptor`, `Path` from `casehub-platform-api`.
- Produces: `EndpointProvisionHandler` (provision/deprovision), `EndpointDriftChecker` (check). Both consumed by the `DeploymentNodeProvisioner` and `DeploymentActualStateAdapter` via CDI.

- [ ] **Step 1: Write EndpointProvisionHandler test**

Create `deployment/src/test/java/io/casehub/ops/deployment/handler/EndpointProvisionHandlerTest.java`:

```java
package io.casehub.ops.deployment.handler;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointProvisionHandlerTest {

    private EndpointProvisionHandler handler;
    private StubEndpointRegistry registry;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        registry = new StubEndpointRegistry();
        handler = new EndpointProvisionHandler(registry);
    }

    @Test
    void provisionRegistersEndpoint() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var context = new ProvisionContext(TENANCY_ID, new DefaultDesiredStateGraphFactory().empty());

        var result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        var registered = registry.resolve(Path.parse("streams/vitals"), TENANCY_ID);
        assertThat(registered).isPresent();
        assertThat(registered.get().protocol()).isEqualTo(EndpointProtocol.KAFKA);
        assertThat(registered.get().properties()).containsEntry(EndpointPropertyKeys.TOPIC, "patient.vitals");
        assertThat(registered.get().tenancyId()).isEqualTo(TENANCY_ID);
    }

    @Test
    void provisionMapsAllFields() {
        var spec = new EndpointNodeSpec(
                "services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://api:8080"),
                "api-creds", Set.of(EndpointCapability.QUERY, EndpointCapability.DISPATCH));
        var context = new ProvisionContext(TENANCY_ID, new DefaultDesiredStateGraphFactory().empty());

        handler.provision(spec, context);

        var registered = registry.resolve(Path.parse("services/api"), TENANCY_ID).orElseThrow();
        assertThat(registered.type()).isEqualTo(EndpointType.SERVICE);
        assertThat(registered.protocol()).isEqualTo(EndpointProtocol.HTTP);
        assertThat(registered.credentialRef()).isEqualTo("api-creds");
        assertThat(registered.capabilities()).containsExactlyInAnyOrder(
                EndpointCapability.QUERY, EndpointCapability.DISPATCH);
    }

    @Test
    void provisionWithNullCredentialRef() {
        var spec = kafkaSpec("streams/events", "events");
        var context = new ProvisionContext(TENANCY_ID, new DefaultDesiredStateGraphFactory().empty());

        handler.provision(spec, context);

        var registered = registry.resolve(Path.parse("streams/events"), TENANCY_ID).orElseThrow();
        assertThat(registered.credentialRef()).isNull();
    }

    @Test
    void deprovisionDeregistersEndpoint() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var graph = new DefaultDesiredStateGraphFactory().empty();
        handler.provision(spec, new ProvisionContext(TENANCY_ID, graph));
        assertThat(registry.resolve(Path.parse("streams/vitals"), TENANCY_ID)).isPresent();

        var result = handler.deprovision(spec, new DeprovisionContext(TENANCY_ID, graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(registry.resolve(Path.parse("streams/vitals"), TENANCY_ID)).isEmpty();
    }

    private EndpointNodeSpec kafkaSpec(String path, String topic) {
        return new EndpointNodeSpec(path, EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, topic), null,
                Set.of(EndpointCapability.RECEIVE));
    }

    static class StubEndpointRegistry implements EndpointRegistry {
        private final Map<String, EndpointDescriptor> endpoints = new ConcurrentHashMap<>();

        private String key(Path path, String tenancyId) {
            return path.value() + ":" + tenancyId;
        }

        @Override
        public void register(EndpointDescriptor endpoint) {
            endpoints.put(key(endpoint.path(), endpoint.tenancyId()), endpoint);
        }

        @Override
        public Optional<EndpointDescriptor> resolve(Path path, String tenancyId) {
            return Optional.ofNullable(endpoints.get(key(path, tenancyId)));
        }

        @Override
        public List<EndpointDescriptor> discover(EndpointQuery query) {
            return new ArrayList<>(endpoints.values());
        }

        @Override
        public void deregister(Path path, String tenancyId) {
            endpoints.remove(key(path, tenancyId));
        }
    }
}
```

- [ ] **Step 2: Write EndpointDriftChecker test**

Create `deployment/src/test/java/io/casehub/ops/deployment/drift/EndpointDriftCheckerTest.java`:

```java
package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.ops.deployment.handler.EndpointProvisionHandlerTest.StubEndpointRegistry;
import io.casehub.platform.api.endpoints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointDriftCheckerTest {

    private EndpointDriftChecker checker;
    private StubEndpointRegistry registry;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        registry = new StubEndpointRegistry();
        checker = new EndpointDriftChecker(registry);
    }

    @Test
    void nodeType() {
        assertEquals("endpoint", checker.nodeType());
    }

    @Test
    void endpointPresent() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        registry.register(spec.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointAbsent() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");

        assertEquals(NodeStatus.ABSENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_protocolChange() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var drifted = new EndpointNodeSpec("streams/vitals", EndpointType.SERVICE, EndpointProtocol.AMQP,
                Map.of(), null, Set.of(EndpointCapability.RECEIVE));
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_propertiesChange() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var drifted = kafkaSpec("streams/vitals", "different.topic");
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_capabilitiesChange() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var drifted = new EndpointNodeSpec("streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "patient.vitals"), null,
                Set.of(EndpointCapability.SEND));
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_credentialRefChange() {
        var spec = new EndpointNodeSpec("services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://api:8080"), "creds-v1", Set.of());
        var drifted = new EndpointNodeSpec("services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://api:8080"), "creds-v2", Set.of());
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void unknownSpecType() {
        var spec = new AgentNodeSpec(
                "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", null, null, null, null, null, List.of(), null, null, null, null, List.of());

        assertEquals(NodeStatus.UNKNOWN, checker.check(spec, TENANCY_ID));
    }

    private EndpointNodeSpec kafkaSpec(String path, String topic) {
        return new EndpointNodeSpec(path, EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, topic), null,
                Set.of(EndpointCapability.RECEIVE));
    }
}
```

- [ ] **Step 3: Create EndpointProvisionHandler**

Create `deployment/src/main/java/io/casehub/ops/deployment/handler/EndpointProvisionHandler.java` — exact content from spec: `register(spec.toDescriptor(context.tenancyId()))`, `deregister(Path.parse(spec.path()), context.tenancyId())`.

- [ ] **Step 4: Create EndpointDriftChecker**

Create `deployment/src/main/java/io/casehub/ops/deployment/drift/EndpointDriftChecker.java` — exact content from spec: `toDescriptor()` + `equals()` comparison.

- [ ] **Step 5: Update DeploymentGoalCompiler**

Add `compileEntries(goals.endpoints(), nodes, dependencies);` after the trust line in `compile()`.

- [ ] **Step 6: Update DeploymentNodeProvisioner**

The `@Inject` constructor currently takes 6 parameters: `AgentRegistry`, `DeploymentProviderConfigStore`, `ChannelProvisionHandler`, `CaseTypeProvisionHandler`, `TrustPolicyProvisionHandler`, `SpecHashStore`.

Add `EndpointProvisionHandler` as the 7th parameter. Store as a field. Add the switch case `case EndpointNodeSpec s -> endpointHandler.provision(s, context);` in both `provision()` and `deprovision()`. Tests construct the provisioner with explicit args — pass the new handler in `DeploymentNodeProvisionerTest.setUp()` and `DeploymentLifecycleIntegrationTest.setUp()`.

- [ ] **Step 7: Update DeploymentGoalLoader merge**

Add endpoint concatenation in `merge()`.

- [ ] **Step 8: Fix all DeploymentGoals constructor calls in tests**

Every test file that constructs `DeploymentGoals` needs a 5th `List.of()` argument for `endpoints`. Files to update:
- `DeploymentGoalCompilerTest.java` — all `new DeploymentGoals(...)` calls
- `DeploymentGoalLoaderTest.java` — `mergesConcatenatesLists()` method
- `DeploymentNodeProvisionerTest.java` — `setUp()` method (provisioner constructor)
- `DeploymentProviderConfigStoreTest.java` — if it constructs DeploymentGoals
- `DeploymentLifecycleIntegrationTest.java` — `setUp()` and test methods
- `DeploymentActualStateAdapterTest.java` — if it constructs DeploymentGoals

Use IntelliJ MCP `ide_find_references` on `DeploymentGoals` to find all call sites before editing.

- [ ] **Step 9: Run all deployment module tests**

Run: `mvn -pl deployment test --batch-mode`
Expected: All tests pass — existing tests updated with 5th constructor arg, new handler and drift checker tests pass.

- [ ] **Step 10: Commit**

```
feat(#6): endpoint provisioning — handler, drift checker, wiring

Adds EndpointProvisionHandler (registers via EndpointRegistry),
EndpointDriftChecker (toDescriptor + equals comparison), and wires
both into the deployment module: compiler, provisioner switch, loader
merge. All DeploymentGoals constructor calls updated for endpoints field.
```

---

### Task 3: Integration test + test YAML

**Files:**
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/DeploymentLifecycleIntegrationTest.java`
- Modify: `deployment/src/test/resources/test-deployment/topology.yaml`

**Interfaces:**
- Consumes: All production code from Tasks 1 and 2. `StubEndpointRegistry` pattern from `EndpointProvisionHandlerTest`.

- [ ] **Step 1: Add StubEndpointRegistry to the integration test**

Add a `StubEndpointRegistry` inner class to `DeploymentLifecycleIntegrationTest` (same pattern as `StubAgentRegistry` and `StubChannelOperations` already in the test — `ConcurrentHashMap`, implements `EndpointRegistry`).

- [ ] **Step 2: Wire EndpointProvisionHandler and EndpointDriftChecker in setUp**

In `setUp()`, create the stub registry, handler, and drift checker. Pass the handler to `DeploymentNodeProvisioner` constructor. Add `EndpointDriftChecker` to the list of drift checkers passed to `DeploymentActualStateAdapter`.

- [ ] **Step 3: Add endpoint to the fullLifecycle test**

In `fullLifecycle_declare_compile_provision_readState()`:
- Create an `EndpointNodeSpec` (Kafka endpoint, `tools/github` as MCP, etc.)
- Add to `DeploymentGoals` with `dependsOn` from agent to endpoint
- Assert graph has 5 nodes (was 4)
- Assert `dependencies()` includes the cross-type dependency
- Provision all nodes, verify all PRESENT

- [ ] **Step 4: Add endpoint drift detection test**

Add a test `driftDetection_endpointPropertyChangeReportsDrifted()`:
- Create and provision a KAFKA endpoint
- Create a modified endpoint (different topic)
- Compile with modified goals
- `adapter.readActual(modifiedDesired)` → assert DRIFTED

- [ ] **Step 5: Add endpoints section to test YAML**

In `deployment/src/test/resources/test-deployment/topology.yaml`, add:

```yaml
endpoints:
  - spec:
      path: test/kafka-stream
      type: SERVICE
      protocol: KAFKA
      properties:
        topic: test.events
      capabilities: [RECEIVE]
    dependsOn: []
```

- [ ] **Step 6: Update DeploymentGoalLoaderTest for endpoints**

In `loadsSingleFile()`, add assertion: `assertThat(goals.endpoints()).hasSize(1)`.

Add a new test `loadsEndpointFromYaml()`:
```java
@Test
void loadsEndpointFromYaml() {
    DeploymentGoals goals = loader.load("test-deployment/topology.yaml");
    assertThat(goals.endpoints()).hasSize(1);
    var endpoint = goals.endpoints().get(0).spec();
    assertThat(endpoint.path()).isEqualTo("test/kafka-stream");
    assertThat(endpoint.protocol()).isEqualTo(EndpointProtocol.KAFKA);
    assertThat(endpoint.properties()).containsEntry(EndpointPropertyKeys.TOPIC, "test.events");
}
```

- [ ] **Step 7: Run full test suite**

Run: `mvn --batch-mode install`
Expected: All modules compile and all tests pass.

- [ ] **Step 8: Commit**

```
test(#6): integration test — endpoint node in full lifecycle

Extends DeploymentLifecycleIntegrationTest with endpoint provisioning,
cross-type dependency (agent → endpoint), and drift detection.
Adds endpoint section to test YAML. Full 5-node lifecycle verified.
```

---

## Verification

After all tasks complete:

1. `mvn --batch-mode install` — all modules compile, all tests pass
2. Verify endpoint compilation: `EndpointNodeSpecTest` exercises all 7 protocols, validation, immutability, `toDescriptor()`
3. Verify provisioning: `EndpointProvisionHandlerTest` confirms register/deregister via stub registry
4. Verify drift: `EndpointDriftCheckerTest` covers ABSENT/PRESENT/DRIFTED for all field changes
5. Verify integration: `DeploymentLifecycleIntegrationTest` runs the full 5-node lifecycle with cross-type dependencies
6. Verify YAML: `DeploymentGoalLoaderTest` parses endpoints from test YAML file
