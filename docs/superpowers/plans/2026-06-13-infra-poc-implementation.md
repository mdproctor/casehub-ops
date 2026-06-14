# Infra Module PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the proof-of-concept infra module from the approved spec (casehubio/casehub-ops#1), validating that the casehub-desiredstate generic runtime's SPI contracts work for infrastructure provisioning.

**Architecture:** Three layers — generic desiredstate SPIs (casehub-desiredstate-api), infra domain types + SPIs (casehub-ops-api), and infra implementations (casehub-ops-infra). The InfraNodeProvisioner dispatches to InfraBackend implementations by backendId. StandaloneBackend delegates task execution to ResourceProvisioner strategies. All I/O-bound SPI methods return `Uni<T>`.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny (reactive), CDI (provider discovery), Jackson (YAML parsing), JUnit 5 + AssertJ (testing)

**Repos touched:**
- `casehub-desiredstate` — prerequisite: create minimal api types (no Java source exists yet)
- `casehub-ops` — main implementation

**Spec:** `docs/superpowers/specs/2026-06-12-infra-terraform-ansible-adapter-design.md`

---

## File Structure

### casehub-desiredstate-api (prerequisite — minimal types for infra to compile)

```
api/src/main/java/io/casehub/desiredstate/api/
├── NodeSpec.java                    — marker interface for domain-opaque node specs
├── NodeId.java                      — value type wrapping String
├── NodeType.java                    — value type wrapping String
├── DesiredNode.java                 — record: id, type, spec, requiresHuman
├── Dependency.java                  — record: from NodeId, to NodeId
├── DesiredStateGraph.java           — record: List<DesiredNode>, List<Dependency>
├── ActualState.java                 — record: Map<NodeId, NodeStatus>
├── NodeStatus.java                  — enum: PRESENT, ABSENT, DRIFTED, UNKNOWN
├── spi/
│   ├── GoalCompiler.java            — interface: compile(G, Constraints, DomainData) → DesiredStateGraph
│   ├── ActualStateAdapter.java      — interface: readActual(DesiredStateGraph) → Uni<ActualState>
│   ├── NodeProvisioner.java         — interface: provision/deprovision → Uni<ProvisionResult>
│   ├── FaultPolicy.java             — interface: onFault(FaultEvent) → GraphMutation
│   └── EventSource.java             — interface: stream() → Multi<StateEvent>
├── Constraints.java                 — marker interface
├── DomainData.java                  — marker interface
├── ProvisionContext.java            — record: nodeId, tenancyId, approvalState
├── DeprovisionContext.java          — record: nodeId, tenancyId
├── ProvisionResult.java             — sealed: Provisioned, Failed, PendingApproval
├── DeprovisionResult.java           — sealed: Deprovisioned, Failed
├── FaultEvent.java                  — record: nodeId, faultType, detail
├── GraphMutation.java               — sealed: AddNode, RemoveNode, UpdateNode
└── StateEvent.java                  — record: nodeId, eventType, timestamp
```

### casehub-ops-api (infra domain types + SPIs)

```
api/src/main/java/io/casehub/ops/api/
├── infra/
│   ├── InfraNodeSpec.java           — sealed interface (does NOT extend NodeSpec)
│   ├── InfraDesiredNodeSpec.java     — record implements NodeSpec (WHAT + HOW wrapper)
│   ├── k8s/
│   │   ├── K8sNamespaceSpec.java
│   │   ├── K8sDeploymentSpec.java
│   │   ├── K8sServiceSpec.java
│   │   └── K8sIngressSpec.java
│   ├── cloud/
│   │   ├── ComputeInstanceSpec.java
│   │   └── DatabaseClusterSpec.java
│   ├── wrapping/
│   │   ├── TerraformWorkspaceSpec.java
│   │   └── AnsiblePlaybookSpec.java
│   ├── GenericResourceSpec.java
│   ├── types/                       — supporting domain types
│   │   ├── Labels.java
│   │   ├── ServiceType.java
│   │   ├── IngressRule.java
│   │   ├── CloudProvider.java
│   │   ├── DatabaseEngine.java
│   │   ├── InstanceType.java
│   │   ├── ClusterSize.java
│   │   ├── ResourceRequirements.java
│   │   ├── NetworkConfig.java
│   │   ├── BackupConfig.java
│   │   ├── TerraformBackendConfig.java
│   │   ├── TerraformStateType.java
│   │   ├── AnsibleInventory.java
│   │   └── AnsibleExtraVars.java
│   ├── spi/
│   │   ├── InfraBackend.java        — reactive SPI (Uni<T>), takes domain types
│   │   ├── BackendProvisionResult.java  — sealed hierarchy
│   │   ├── BackendDeprovisionResult.java
│   │   └── ResourceProvisioner.java — task execution SPI (Uni<T>)
│   ├── context/
│   │   ├── InfraProvisionContext.java
│   │   ├── ProvisionPhase.java
│   │   ├── ProvisionAction.java
│   │   ├── RiskClassification.java
│   │   └── RiskThresholds.java
│   ├── state/
│   │   ├── ResourceState.java
│   │   ├── ResourceStatus.java
│   │   ├── ResourceOutputs.java
│   │   ├── DriftReport.java
│   │   └── DriftedField.java
│   ├── task/
│   │   ├── ProvisionTask.java
│   │   ├── TaskAction.java
│   │   ├── ProvisionOutcome.java
│   │   ├── ExecutionArtifact.java
│   │   ├── ArtifactType.java
│   │   └── ArtifactProvenance.java  — sealed: HandWritten, LlmGenerated, CachedReuse
│   ├── plan/
│   │   ├── ProvisionPlan.java
│   │   ├── PlannedChange.java
│   │   ├── ChangeAction.java
│   │   ├── ToolPlanDetail.java      — sealed: TerraformPlanDetail, AnsibleCheckDetail, StandaloneDiffDetail
│   │   └── FieldDiff.java
│   └── goal/
│       ├── InfraGoals.java
│       ├── ResourceDeclaration.java
│       └── ImportDeclaration.java

api/src/test/java/io/casehub/ops/api/infra/
├── InfraNodeSpecTest.java
├── InfraDesiredNodeSpecTest.java
└── types/LabelsTest.java
```

### casehub-ops-infra (implementations)

```
infra/src/main/java/io/casehub/ops/infra/
├── InfraGoalCompiler.java
├── InfraNodeProvisioner.java
├── InfraActualStateAdapter.java
├── InfraFaultPolicy.java
├── InfraEventSource.java
└── standalone/
    ├── StandaloneBackend.java
    └── InMemoryResourceProvisioner.java

infra/src/test/java/io/casehub/ops/infra/
├── InfraGoalCompilerTest.java
├── InfraNodeProvisionerTest.java
├── InfraActualStateAdapterTest.java
├── InfraFaultPolicyTest.java
├── InfraEventSourceTest.java
├── standalone/
│   ├── StandaloneBackendTest.java
│   └── InMemoryResourceProvisionerTest.java
└── InfraLifecycleIntegrationTest.java
```

---

## Task 1: casehub-desiredstate-api — Core Graph Types and SPIs

**Repo:** `casehub-desiredstate` (prerequisite — creates the minimal types the infra module compiles against)

**Files:**
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeSpec.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeId.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeType.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DesiredNode.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/Dependency.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DesiredStateGraph.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ActualState.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/NodeStatus.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/Constraints.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DomainData.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ProvisionContext.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DeprovisionContext.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/ProvisionResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/DeprovisionResult.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/FaultEvent.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/GraphMutation.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/StateEvent.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/spi/GoalCompiler.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/spi/ActualStateAdapter.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/spi/NodeProvisioner.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/spi/FaultPolicy.java`
- Create: `api/src/main/java/io/casehub/desiredstate/api/spi/EventSource.java`
- Test: `api/src/test/java/io/casehub/desiredstate/api/DesiredStateGraphTest.java`

- [ ] **Step 1: Write test for core graph types**

```java
package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateGraphTest {

    @Test
    void graphHoldsNodesAndDependencies() {
        var spec = new TestNodeSpec();
        var nodeA = new DesiredNode(NodeId.of("a"), NodeType.of("test"), spec, false);
        var nodeB = new DesiredNode(NodeId.of("b"), NodeType.of("test"), spec, false);
        var dep = new Dependency(NodeId.of("a"), NodeId.of("b"));
        var graph = new DesiredStateGraph(List.of(nodeA, nodeB), List.of(dep));

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies()).hasSize(1);
        assertThat(graph.dependencies().getFirst().from()).isEqualTo(NodeId.of("a"));
    }

    @Test
    void nodeIdEquality() {
        assertThat(NodeId.of("x")).isEqualTo(NodeId.of("x"));
        assertThat(NodeId.of("x")).isNotEqualTo(NodeId.of("y"));
    }

    @Test
    void humanNodeFlag() {
        var spec = new TestNodeSpec();
        var humanNode = new DesiredNode(NodeId.of("h"), NodeType.of("test"), spec, true);
        assertThat(humanNode.requiresHuman()).isTrue();
    }

    record TestNodeSpec() implements NodeSpec {}
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=DesiredStateGraphTest` (from casehub-desiredstate)
Expected: Compilation failure — types don't exist yet

- [ ] **Step 3: Implement core graph types**

`NodeSpec.java`:
```java
package io.casehub.desiredstate.api;

public interface NodeSpec {}
```

`NodeId.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeId(String value) {
    public NodeId { Objects.requireNonNull(value, "value"); }
    public static NodeId of(String value) { return new NodeId(value); }
}
```

`NodeType.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeType(String value) {
    public NodeType { Objects.requireNonNull(value, "value"); }
    public static NodeType of(String value) { return new NodeType(value); }
}
```

`DesiredNode.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record DesiredNode(NodeId id, NodeType type, NodeSpec spec, boolean requiresHuman) {
    public DesiredNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(spec, "spec");
    }
}
```

`Dependency.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;

public record Dependency(NodeId from, NodeId to) {
    public Dependency {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
```

`DesiredStateGraph.java`:
```java
package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Objects;

public record DesiredStateGraph(List<DesiredNode> nodes, List<Dependency> dependencies) {
    public DesiredStateGraph {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(dependencies, "dependencies");
        nodes = List.copyOf(nodes);
        dependencies = List.copyOf(dependencies);
    }
}
```

`NodeStatus.java`:
```java
package io.casehub.desiredstate.api;

public enum NodeStatus { PRESENT, ABSENT, DRIFTED, UNKNOWN }
```

`ActualState.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ActualState(Map<NodeId, NodeStatus> statuses) {
    public ActualState {
        Objects.requireNonNull(statuses, "statuses");
        statuses = Map.copyOf(statuses);
    }
    public Optional<NodeStatus> statusOf(NodeId nodeId) {
        return Optional.ofNullable(statuses.get(nodeId));
    }
}
```

- [ ] **Step 4: Implement SPIs and remaining types**

`Constraints.java`:
```java
package io.casehub.desiredstate.api;

public interface Constraints {}
```

`DomainData.java`:
```java
package io.casehub.desiredstate.api;

public interface DomainData {}
```

`ProvisionContext.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;
import java.util.Optional;

public record ProvisionContext(
    NodeId nodeId,
    String tenancyId,
    Optional<PlanApproval> approval
) {
    public ProvisionContext {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        if (approval == null) approval = Optional.empty();
    }

    public record PlanApproval(String planReference, String approvedBy, java.time.Instant approvedAt) {}
}
```

`DeprovisionContext.java`:
```java
package io.casehub.desiredstate.api;

import java.util.Objects;
import java.util.Optional;

public record DeprovisionContext(
    NodeId nodeId,
    String tenancyId,
    Optional<ProvisionContext.PlanApproval> approval
) {
    public DeprovisionContext {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        if (approval == null) approval = Optional.empty();
    }
}
```

`ProvisionResult.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface ProvisionResult
    permits ProvisionResult.Provisioned, ProvisionResult.Failed, ProvisionResult.PendingApproval {

    record Provisioned(NodeId nodeId) implements ProvisionResult {}
    record Failed(NodeId nodeId, String reason, boolean retryable) implements ProvisionResult {}
    record PendingApproval(NodeId nodeId, String planReference) implements ProvisionResult {}
}
```

`DeprovisionResult.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface DeprovisionResult
    permits DeprovisionResult.Deprovisioned, DeprovisionResult.Failed {

    record Deprovisioned(NodeId nodeId) implements DeprovisionResult {}
    record Failed(NodeId nodeId, String reason, boolean retryable) implements DeprovisionResult {}
}
```

`FaultEvent.java`:
```java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record FaultEvent(NodeId nodeId, String faultType, String detail, Instant occurredAt) {
    public FaultEvent {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(faultType, "faultType");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
```

`GraphMutation.java`:
```java
package io.casehub.desiredstate.api;

public sealed interface GraphMutation
    permits GraphMutation.AddNode, GraphMutation.RemoveNode, GraphMutation.UpdateNode {

    record AddNode(DesiredNode node) implements GraphMutation {}
    record RemoveNode(NodeId nodeId) implements GraphMutation {}
    record UpdateNode(NodeId nodeId, NodeSpec newSpec) implements GraphMutation {}
}
```

`StateEvent.java`:
```java
package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record StateEvent(NodeId nodeId, StateEventType eventType, Instant timestamp) {
    public StateEvent {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public enum StateEventType { DRIFT_DETECTED, STATE_CHANGED, NODE_UNAVAILABLE, NODE_RECOVERED }
}
```

SPI interfaces in `api/spi/`:

`GoalCompiler.java`:
```java
package io.casehub.desiredstate.api.spi;

import io.casehub.desiredstate.api.Constraints;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DomainData;

public interface GoalCompiler<G> {
    DesiredStateGraph compile(G goals, Constraints constraints, DomainData data);
}
```

`ActualStateAdapter.java`:
```java
package io.casehub.desiredstate.api.spi;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.smallrye.mutiny.Uni;

public interface ActualStateAdapter {
    Uni<ActualState> readActual(DesiredStateGraph desired);
}
```

`NodeProvisioner.java`:
```java
package io.casehub.desiredstate.api.spi;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.smallrye.mutiny.Uni;

public interface NodeProvisioner {
    Uni<ProvisionResult> provision(DesiredNode node, ProvisionContext context);
    Uni<DeprovisionResult> deprovision(DesiredNode node, DeprovisionContext context);
}
```

`FaultPolicy.java`:
```java
package io.casehub.desiredstate.api.spi;

import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.GraphMutation;
import java.util.Optional;

public interface FaultPolicy {
    Optional<GraphMutation> onFault(FaultEvent event);
}
```

`EventSource.java`:
```java
package io.casehub.desiredstate.api.spi;

import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;

public interface EventSource {
    Multi<StateEvent> stream();
}
```

- [ ] **Step 5: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl api` (from casehub-desiredstate)
Expected: All tests PASS

- [ ] **Step 6: Install locally**

Run: `mvn --batch-mode install -pl api -DskipTests` (from casehub-desiredstate)
Expected: `casehub-desiredstate-api` available in local Maven repo

- [ ] **Step 7: Commit**

```
git add api/src/
git commit -m "feat(#1): core graph types and SPIs — minimal desiredstate-api

DesiredStateGraph, DesiredNode, NodeSpec, NodeId, NodeType, Dependency,
ActualState, ProvisionResult (sealed), DeprovisionResult (sealed),
FaultEvent, GraphMutation (sealed), StateEvent.

SPIs: GoalCompiler, ActualStateAdapter, NodeProvisioner (reactive Uni<>),
FaultPolicy, EventSource (reactive Multi<>).

ProvisionContext carries Optional<PlanApproval> for plan/apply lifecycle.
ProvisionResult.PendingApproval enables dynamic human gates.

Minimal set — just enough for casehub-ops infra module to compile."
```

---

## Task 2: casehub-ops-api — InfraNodeSpec Sealed Hierarchy + Supporting Types

**Repo:** `casehub-ops`

**Files:**
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/` (InfraNodeSpec, K8s specs, cloud specs, wrapping specs, generic spec)
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/types/` (Labels, enums, value records)
- Test: `api/src/test/java/io/casehub/ops/api/infra/InfraNodeSpecTest.java`

- [ ] **Step 1: Write test for InfraNodeSpec hierarchy**

```java
package io.casehub.ops.api.infra;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.ops.api.infra.k8s.K8sDeploymentSpec;
import io.casehub.ops.api.infra.k8s.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ops.api.infra.wrapping.TerraformWorkspaceSpec;
import io.casehub.ops.api.infra.wrapping.AnsiblePlaybookSpec;
import io.casehub.ops.api.infra.types.TerraformBackendConfig;
import io.casehub.ops.api.infra.types.TerraformStateType;
import io.casehub.ops.api.infra.types.AnsibleInventory;
import io.casehub.ops.api.infra.types.AnsibleExtraVars;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InfraNodeSpecTest {

    @Test
    void infraNodeSpecIsNotNodeSpec() {
        assertThat(NodeSpec.class.isAssignableFrom(InfraNodeSpec.class)).isFalse();
    }

    @Test
    void infraDesiredNodeSpecIsNodeSpec() {
        assertThat(NodeSpec.class.isAssignableFrom(InfraDesiredNodeSpec.class)).isTrue();
    }

    @Test
    void k8sNamespaceSpecHasResourceType() {
        var spec = new K8sNamespaceSpec("my-ns", Labels.empty());
        assertThat(spec.resourceType()).isEqualTo("k8s_namespace");
    }

    @Test
    void k8sDeploymentSpecHasResourceType() {
        var spec = new K8sDeploymentSpec("default", "my-app", "my-app:latest",
            3, new ResourceRequirements("100m", "500m", "128Mi", "512Mi"), Labels.empty());
        assertThat(spec.resourceType()).isEqualTo("k8s_deployment");
        assertThat(spec.replicas()).isEqualTo(3);
    }

    @Test
    void wrapperComposesSpecAndBackend() {
        var nsSpec = new K8sNamespaceSpec("my-ns", Labels.empty());
        var wrapper = new InfraDesiredNodeSpec(nsSpec, "standalone");

        assertThat(wrapper.resourceSpec()).isEqualTo(nsSpec);
        assertThat(wrapper.backendId()).isEqualTo("standalone");
    }

    @Test
    void terraformWorkspaceSpecHasResourceType() {
        var spec = new TerraformWorkspaceSpec("./tf", new TerraformBackendConfig(TerraformStateType.LOCAL, null, null));
        assertThat(spec.resourceType()).isEqualTo("terraform_workspace");
    }

    @Test
    void ansiblePlaybookSpecHasResourceType() {
        var spec = new AnsiblePlaybookSpec("./play.yml",
            new AnsibleInventory("./inv", "web"), AnsibleExtraVars.empty());
        assertThat(spec.resourceType()).isEqualTo("ansible_playbook");
    }

    @Test
    void patternMatchingOnSealedHierarchy() {
        InfraNodeSpec spec = new K8sNamespaceSpec("ns", Labels.empty());
        String type = switch (spec) {
            case K8sNamespaceSpec ns -> "namespace:" + ns.name();
            case K8sDeploymentSpec d -> "deploy:" + d.name();
            default -> "other:" + spec.resourceType();
        };
        assertThat(type).isEqualTo("namespace:ns");
    }

    @Test
    void labelsAccessor() {
        var labels = Labels.of(Map.of("env", "prod", "team", "infra"));
        assertThat(labels.get("env")).contains("prod");
        assertThat(labels.get("missing")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=InfraNodeSpecTest`
Expected: Compilation failure — types don't exist

- [ ] **Step 3: Implement InfraNodeSpec hierarchy and supporting types**

Implement all files listed in the file structure above. The spec (§4.1-4.3) contains the complete type definitions. Key implementation notes:
- `InfraNodeSpec` is a sealed interface that does NOT extend `NodeSpec`
- `InfraDesiredNodeSpec` implements `NodeSpec` — this is the only `NodeSpec` in the infra domain
- `Labels` wraps `Map<String, String>` with `of()` factory and `empty()` factory
- `AnsibleExtraVars` wraps `Map<String, String>` with `empty()` factory
- `GenericResourceSpec` uses `JsonNode` for untyped config — add Jackson dependency to api/pom.xml
- All records use defensive copying for collections (`List.copyOf`, `Map.copyOf`)

Add to `api/pom.xml`:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=InfraNodeSpecTest`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```
git add api/
git commit -m "feat(#1): InfraNodeSpec sealed hierarchy + supporting types

K8s types: K8sNamespaceSpec, K8sDeploymentSpec, K8sServiceSpec, K8sIngressSpec
Cloud types: ComputeInstanceSpec, DatabaseClusterSpec
Wrapping: TerraformWorkspaceSpec, AnsiblePlaybookSpec
Generic: GenericResourceSpec (JsonNode escape hatch)
Wrapper: InfraDesiredNodeSpec implements NodeSpec (WHAT + HOW)

InfraNodeSpec does NOT extend NodeSpec — compile-time enforcement
that DesiredNode always uses the InfraDesiredNodeSpec wrapper."
```

---

## Task 3: casehub-ops-api — InfraBackend SPI + Result Types + Context Types

**Repo:** `casehub-ops`

**Files:**
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/spi/`
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/context/`
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/state/`
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/task/`
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/plan/`
- Create: All files under `api/src/main/java/io/casehub/ops/api/infra/goal/`
- Test: `api/src/test/java/io/casehub/ops/api/infra/spi/InfraBackendContractTest.java`

- [ ] **Step 1: Write contract test for InfraBackend SPI**

```java
package io.casehub.ops.api.infra.spi;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.context.ProvisionAction;
import io.casehub.ops.api.infra.context.ProvisionPhase;
import io.casehub.ops.api.infra.context.RiskClassification;
import io.casehub.ops.api.infra.context.RiskThresholds;
import io.casehub.ops.api.infra.k8s.K8sNamespaceSpec;
import io.casehub.ops.api.infra.state.DriftReport;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.types.Labels;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InfraBackendContractTest {

    @Test
    void backendProvisionReturnsProvisionedResult() {
        var backend = new StubBackend();
        var spec = new K8sNamespaceSpec("test-ns", Labels.empty());
        var ctx = new InfraProvisionContext(
            NodeId.of("n1"), "tenant-1", ProvisionPhase.APPLY, ProvisionAction.PROVISION,
            null, new RiskThresholds(RiskClassification.LOW, false), Instant.now());

        var result = backend.provision(spec, ctx).await().indefinitely();

        assertThat(result).isInstanceOf(BackendProvisionResult.Provisioned.class);
        var provisioned = (BackendProvisionResult.Provisioned) result;
        assertThat(provisioned.state().status()).isEqualTo(ResourceStatus.HEALTHY);
    }

    @Test
    void backendReadStateReturnsResourceState() {
        var backend = new StubBackend();
        var state = backend.readState(NodeId.of("n1")).await().indefinitely();
        assertThat(state.nodeId()).isEqualTo(NodeId.of("n1"));
    }

    @Test
    void backendDetectDriftReturnsDriftReport() {
        var backend = new StubBackend();
        var report = backend.detectDrift(NodeId.of("n1")).await().indefinitely();
        assertThat(report.drifted()).isFalse();
    }

    static class StubBackend implements InfraBackend {
        @Override public String backendId() { return "stub"; }

        @Override public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext ctx) {
            var state = new ResourceState(ctx.nodeId(), spec.resourceType(),
                ResourceStatus.HEALTHY, Instant.now(), null, ResourceOutputs.empty());
            return Uni.createFrom().item(new BackendProvisionResult.Provisioned(state));
        }

        @Override public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext ctx) {
            return Uni.createFrom().item(new BackendDeprovisionResult.Deprovisioned(ctx.nodeId()));
        }

        @Override public Uni<ResourceState> readState(NodeId nodeId) {
            return Uni.createFrom().item(new ResourceState(nodeId, "test",
                ResourceStatus.HEALTHY, Instant.now(), null, ResourceOutputs.empty()));
        }

        @Override public Uni<DriftReport> detectDrift(NodeId nodeId) {
            return Uni.createFrom().item(new DriftReport(nodeId, false, List.of(), Instant.now(), "stub"));
        }

        @Override public Uni<Optional<io.casehub.ops.api.infra.plan.ProvisionPlan>> plan(
                InfraNodeSpec spec, InfraProvisionContext ctx) {
            return Uni.createFrom().item(Optional.empty());
        }
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn --batch-mode test -pl api -Dtest=InfraBackendContractTest`
Expected: Compilation failure

- [ ] **Step 3: Implement all SPI, context, state, task, plan, and goal types**

Implement all types from the spec §4.4-4.7, §5, §6, §11.4. The spec contains the complete definitions. Key notes:
- `InfraBackend` — all methods return `Uni<T>`, takes domain types not runtime types
- `BackendProvisionResult` / `BackendDeprovisionResult` — sealed hierarchies (§5.1)
- `ResourceProvisioner` — `handles()` + `execute()` returning `Uni<ProvisionOutcome>` (§6)
- `InfraProvisionContext` — carries phase, action, approved plan, thresholds (§4.6)
- `ResourceState` — nodeId, resourceType, status, lastObserved, attributes, outputs (§4.4)
- `DriftReport` — nodeId, drifted, drifts list, detectedAt, backendId (§4.5)
- `ProvisionPlan` — nodeId, changes, risk, summary, toolDetail (§11.4)
- `InfraGoals` — defaultBackend, resources, imports (§4.7)

- [ ] **Step 4: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl api`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```
git add api/
git commit -m "feat(#1): InfraBackend SPI, result types, context types, state types

InfraBackend: reactive SPI (Uni<T>) with provision/deprovision/readState/
detectDrift/plan — takes domain types, not runtime types.
BackendProvisionResult + BackendDeprovisionResult: sealed hierarchies.
ResourceProvisioner: task execution SPI for standalone backend.
InfraProvisionContext: phase (PLAN/APPLY), action (PROVISION/DEPROVISION),
approved plan, risk thresholds, tenant context.
ResourceState, DriftReport, ProvisionPlan, InfraGoals."
```

---

## Task 4: casehub-ops-infra — InfraGoalCompiler

**Repo:** `casehub-ops`

**Files:**
- Create: `infra/src/main/java/io/casehub/ops/infra/InfraGoalCompiler.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/InfraGoalCompilerTest.java`

- [ ] **Step 1: Write test for goal compilation**

```java
package io.casehub.ops.infra;

import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.goal.InfraGoals;
import io.casehub.ops.api.infra.goal.ResourceDeclaration;
import io.casehub.ops.api.infra.k8s.K8sNamespaceSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfraGoalCompilerTest {

    private final InfraGoalCompiler compiler = new InfraGoalCompiler();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compilesK8sNamespaceToTypedSpec() {
        var config = mapper.createObjectNode().put("name", "my-ns");
        var decl = new ResourceDeclaration("ns1", "k8s_namespace", null, config, List.of());
        var goals = new InfraGoals("standalone", List.of(decl), List.of());

        var graph = compiler.compile(goals, null, null);

        assertThat(graph.nodes()).hasSize(1);
        var node = graph.nodes().getFirst();
        assertThat(node.spec()).isInstanceOf(InfraDesiredNodeSpec.class);
        var infraSpec = (InfraDesiredNodeSpec) node.spec();
        assertThat(infraSpec.backendId()).isEqualTo("standalone");
        assertThat(infraSpec.resourceSpec()).isInstanceOf(K8sNamespaceSpec.class);
        assertThat(((K8sNamespaceSpec) infraSpec.resourceSpec()).name()).isEqualTo("my-ns");
    }

    @Test
    void perResourceBackendOverridesDefault() {
        var config = mapper.createObjectNode().put("name", "my-ns");
        var decl = new ResourceDeclaration("ns1", "k8s_namespace", "terraform", config, List.of());
        var goals = new InfraGoals("standalone", List.of(decl), List.of());

        var graph = compiler.compile(goals, null, null);

        var infraSpec = (InfraDesiredNodeSpec) graph.nodes().getFirst().spec();
        assertThat(infraSpec.backendId()).isEqualTo("terraform");
    }

    @Test
    void dependsOnCreatesDependencyEdges() {
        var nsConfig = mapper.createObjectNode().put("name", "my-ns");
        var deployConfig = mapper.createObjectNode()
            .put("namespace", "my-ns").put("name", "app").put("image", "app:1").put("replicas", 1);
        var ns = new ResourceDeclaration("ns1", "k8s_namespace", null, nsConfig, List.of());
        var deploy = new ResourceDeclaration("d1", "k8s_deployment", null, deployConfig, List.of("ns1"));
        var goals = new InfraGoals("standalone", List.of(ns, deploy), List.of());

        var graph = compiler.compile(goals, null, null);

        assertThat(graph.dependencies()).hasSize(1);
        assertThat(graph.dependencies().getFirst().from().value()).isEqualTo("ns1");
        assertThat(graph.dependencies().getFirst().to().value()).isEqualTo("d1");
    }

    @Test
    void unknownTypeProducesGenericResourceSpec() {
        var config = mapper.createObjectNode().put("foo", "bar");
        var decl = new ResourceDeclaration("x1", "custom_widget", null, config, List.of());
        var goals = new InfraGoals("standalone", List.of(decl), List.of());

        var graph = compiler.compile(goals, null, null);
        var infraSpec = (InfraDesiredNodeSpec) graph.nodes().getFirst().spec();
        assertThat(infraSpec.resourceSpec().resourceType()).isEqualTo("custom_widget");
    }

    @Test
    void rejectsTerraformWorkspaceWithAnsibleBackend() {
        var config = mapper.createObjectNode().put("workspacePath", "./tf");
        var decl = new ResourceDeclaration("w1", "terraform_workspace", "ansible", config, List.of());
        var goals = new InfraGoals("standalone", List.of(decl), List.of());

        assertThatThrownBy(() -> compiler.compile(goals, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("incompatible backend");
    }

    @Test
    void fallsBackToStandaloneWhenNoBackendSpecified() {
        var config = mapper.createObjectNode().put("name", "ns");
        var decl = new ResourceDeclaration("ns1", "k8s_namespace", null, config, List.of());
        var goals = new InfraGoals(null, List.of(decl), List.of());

        var graph = compiler.compile(goals, null, null);
        var infraSpec = (InfraDesiredNodeSpec) graph.nodes().getFirst().spec();
        assertThat(infraSpec.backendId()).isEqualTo("standalone");
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn --batch-mode test -pl infra -Dtest=InfraGoalCompilerTest`
Expected: Compilation failure

- [ ] **Step 3: Implement InfraGoalCompiler**

The compiler:
1. Iterates `ResourceDeclaration` and `ImportDeclaration` entries
2. For each: parses `config` JsonNode into the typed `InfraNodeSpec` via pattern matching on `type`
3. Resolves `backendId`: per-resource override > top-level default > `"standalone"` fallback
4. Validates backend compatibility (terraform_workspace + ansible = error)
5. Wraps in `InfraDesiredNodeSpec(resourceSpec, backendId)`
6. Creates `DesiredNode` with the wrapper as spec
7. Creates `Dependency` edges from `dependsOn` lists

Add Jackson YAML dependency to `infra/pom.xml`:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

- [ ] **Step 4: Run tests — verify they pass**

Run: `mvn --batch-mode test -pl infra -Dtest=InfraGoalCompilerTest`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```
git add infra/
git commit -m "feat(#1): InfraGoalCompiler — YAML goals to DesiredStateGraph

Parses ResourceDeclaration/ImportDeclaration into typed InfraNodeSpec.
Resolves backend routing: per-resource > top-level > standalone fallback.
Validates type/backend compatibility. Wraps in InfraDesiredNodeSpec.
Unknown types produce GenericResourceSpec."
```

---

## Task 5: casehub-ops-infra — InfraNodeProvisioner + InfraActualStateAdapter

**Repo:** `casehub-ops`

**Files:**
- Create: `infra/src/main/java/io/casehub/ops/infra/InfraNodeProvisioner.java`
- Create: `infra/src/main/java/io/casehub/ops/infra/InfraActualStateAdapter.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/InfraNodeProvisionerTest.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/InfraActualStateAdapterTest.java`

- [ ] **Step 1: Write test for InfraNodeProvisioner**

```java
package io.casehub.ops.infra;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.api.infra.context.*;
import io.casehub.ops.api.infra.k8s.K8sNamespaceSpec;
import io.casehub.ops.api.infra.spi.*;
import io.casehub.ops.api.infra.state.*;
import io.casehub.ops.api.infra.types.Labels;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InfraNodeProvisionerTest {

    @Test
    void dispatchesToCorrectBackendByBackendId() {
        var spec = new K8sNamespaceSpec("test", Labels.empty());
        var wrapper = new InfraDesiredNodeSpec(spec, "standalone");
        var node = new DesiredNode(NodeId.of("n1"), NodeType.of("k8s_namespace"), wrapper, false);
        var ctx = new ProvisionContext(NodeId.of("n1"), "tenant-1", Optional.empty());

        var trackingBackend = new TrackingBackend("standalone");
        var provisioner = new InfraNodeProvisioner(List.of(trackingBackend));

        var result = provisioner.provision(node, ctx).await().indefinitely();

        assertThat(result).isInstanceOf(ProvisionResult.Provisioned.class);
        assertThat(trackingBackend.lastProvisionedSpec).isEqualTo(spec);
    }

    @Test
    void failsWhenNoBackendMatchesBackendId() {
        var spec = new K8sNamespaceSpec("test", Labels.empty());
        var wrapper = new InfraDesiredNodeSpec(spec, "nonexistent");
        var node = new DesiredNode(NodeId.of("n1"), NodeType.of("k8s_namespace"), wrapper, false);
        var ctx = new ProvisionContext(NodeId.of("n1"), "tenant-1", Optional.empty());

        var provisioner = new InfraNodeProvisioner(List.of(new TrackingBackend("standalone")));

        var result = provisioner.provision(node, ctx).await().indefinitely();

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("No backend");
    }

    static class TrackingBackend implements InfraBackend {
        final String id;
        InfraNodeSpec lastProvisionedSpec;

        TrackingBackend(String id) { this.id = id; }
        @Override public String backendId() { return id; }

        @Override public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext ctx) {
            this.lastProvisionedSpec = spec;
            var state = new ResourceState(ctx.nodeId(), spec.resourceType(),
                ResourceStatus.HEALTHY, Instant.now(), null, ResourceOutputs.empty());
            return Uni.createFrom().item(new BackendProvisionResult.Provisioned(state));
        }

        @Override public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec s, InfraProvisionContext c) {
            return Uni.createFrom().item(new BackendDeprovisionResult.Deprovisioned(c.nodeId()));
        }
        @Override public Uni<ResourceState> readState(NodeId id) {
            return Uni.createFrom().item(new ResourceState(id, "test",
                ResourceStatus.HEALTHY, Instant.now(), null, ResourceOutputs.empty()));
        }
        @Override public Uni<DriftReport> detectDrift(NodeId id) {
            return Uni.createFrom().item(new DriftReport(id, false, List.of(), Instant.now(), this.id));
        }
        @Override public Uni<Optional<io.casehub.ops.api.infra.plan.ProvisionPlan>> plan(
                InfraNodeSpec s, InfraProvisionContext c) {
            return Uni.createFrom().item(Optional.empty());
        }
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `mvn --batch-mode test -pl infra -Dtest=InfraNodeProvisionerTest`
Expected: Compilation failure

- [ ] **Step 3: Implement InfraNodeProvisioner**

The provisioner:
1. Receives `DesiredNode` + runtime `ProvisionContext`
2. Casts `node.spec()` to `InfraDesiredNodeSpec`
3. Extracts `resourceSpec` (InfraNodeSpec) and `backendId`
4. Looks up `InfraBackend` by `backendId` from injected backends
5. Constructs `InfraProvisionContext` from the runtime context + plan/apply phase detection
6. Calls `backend.provision(resourceSpec, infraCtx)` → maps `BackendProvisionResult` to `ProvisionResult`

For testability, the constructor accepts `List<InfraBackend>` — in production, CDI injects via `@Any Instance<InfraBackend>`.

- [ ] **Step 4: Write test for InfraActualStateAdapter**

Similar pattern — iterates desired nodes, delegates per-node to the correct backend's `readState()`, collects into `ActualState`.

- [ ] **Step 5: Implement InfraActualStateAdapter**

- [ ] **Step 6: Run all tests — verify they pass**

Run: `mvn --batch-mode test -pl infra`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```
git add infra/
git commit -m "feat(#1): InfraNodeProvisioner dispatcher + InfraActualStateAdapter

InfraNodeProvisioner: unwraps InfraDesiredNodeSpec, resolves backend by
backendId, constructs InfraProvisionContext, delegates to InfraBackend.
Maps BackendProvisionResult to runtime ProvisionResult.

InfraActualStateAdapter: iterates desired nodes, delegates per-node
readState() to the correct backend, collects into ActualState."
```

---

## Task 6: casehub-ops-infra — StandaloneBackend + InMemoryResourceProvisioner

**Repo:** `casehub-ops`

**Files:**
- Create: `infra/src/main/java/io/casehub/ops/infra/standalone/StandaloneBackend.java`
- Create: `infra/src/main/java/io/casehub/ops/infra/standalone/InMemoryResourceProvisioner.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/standalone/StandaloneBackendTest.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/standalone/InMemoryResourceProvisionerTest.java`

- [ ] **Step 1: Write test for InMemoryResourceProvisioner**

Tests that the in-memory provisioner handles K8s specs, stores state, and supports CREATE/UPDATE/DESTROY actions.

- [ ] **Step 2: Run test — verify it fails**

- [ ] **Step 3: Implement InMemoryResourceProvisioner**

A `ResourceProvisioner` that stores state in a `ConcurrentHashMap<NodeId, ResourceState>`. Handles all `InfraNodeSpec` types. For CREATE: stores the spec as state. For UPDATE: replaces state. For DESTROY: removes state. Returns `ProvisionOutcome` with success/failure.

`@ApplicationScoped @Priority(0)` — lowest priority, always available, overridden by real provisioners.

- [ ] **Step 4: Write test for StandaloneBackend**

Tests that StandaloneBackend delegates to the ResourceProvisioner selected by priority + handles(), manages state, and supports plan generation via diff.

- [ ] **Step 5: Implement StandaloneBackend**

The backend:
1. `provision()`: resolves ResourceProvisioner for the spec, calls `execute()`, stores result state
2. `deprovision()`: resolves ResourceProvisioner, calls `execute()` with DESTROY, removes state
3. `readState()`: returns stored state or UNKNOWN
4. `detectDrift()`: compares stored desired spec against current state — returns DriftReport
5. `plan()`: diffs desired vs actual to produce `ProvisionPlan` with `StandaloneDiffDetail`

`@ApplicationScoped` — always present as the default backend.

- [ ] **Step 6: Run all tests — verify they pass**

Run: `mvn --batch-mode test -pl infra`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```
git add infra/
git commit -m "feat(#1): StandaloneBackend + InMemoryResourceProvisioner

StandaloneBackend: CaseHub-native provisioning. Delegates task execution
to ResourceProvisioner, manages state in-memory, generates plans via diff.

InMemoryResourceProvisioner: stores state in ConcurrentHashMap.
Handles all InfraNodeSpec types. CREATE/UPDATE/DESTROY actions.
@Priority(0) — default, overridden by real provisioners."
```

---

## Task 7: casehub-ops-infra — InfraFaultPolicy + InfraEventSource

**Repo:** `casehub-ops`

**Files:**
- Create: `infra/src/main/java/io/casehub/ops/infra/InfraFaultPolicy.java`
- Create: `infra/src/main/java/io/casehub/ops/infra/InfraEventSource.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/InfraFaultPolicyTest.java`
- Test: `infra/src/test/java/io/casehub/ops/infra/InfraEventSourceTest.java`

- [ ] **Step 1: Write test for InfraFaultPolicy**

```java
package io.casehub.ops.infra;

import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InfraFaultPolicyTest {

    private final InfraFaultPolicy policy = new InfraFaultPolicy();

    @Test
    void driftDetectedReturnsNoMutationForAutoRemediation() {
        var event = new FaultEvent(NodeId.of("n1"), "DRIFT_DETECTED", "field changed", Instant.now());
        var mutation = policy.onFault(event);
        assertThat(mutation).isEmpty();
    }

    @Test
    void provisionFailedTransientReturnsNoMutationForRetry() {
        var event = new FaultEvent(NodeId.of("n1"), "PROVISION_FAILED_TRANSIENT", "timeout", Instant.now());
        var mutation = policy.onFault(event);
        assertThat(mutation).isEmpty();
    }

    @Test
    void provisionFailedPermanentReturnsRemoveNode() {
        var event = new FaultEvent(NodeId.of("n1"), "PROVISION_FAILED_PERMANENT", "quota exceeded", Instant.now());
        var mutation = policy.onFault(event);
        assertThat(mutation).isPresent();
        assertThat(mutation.get()).isInstanceOf(GraphMutation.RemoveNode.class);
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

- [ ] **Step 3: Implement InfraFaultPolicy**

Default fault rules from spec §10.1. Returns `Optional.empty()` for faults where the runtime handles retry/remediation. Returns `GraphMutation` for faults requiring graph changes.

- [ ] **Step 4: Write test for InfraEventSource**

Test that InfraEventSource emits `StateEvent` from backend drift detection via periodic polling.

- [ ] **Step 5: Implement InfraEventSource**

Combines real-time event streams and periodic polling into a single `Multi<StateEvent>`. For the PoC, uses `Multi.createBy().merging()` to combine:
- Scheduled timer → calls `backend.detectDrift()` → emits `StateEvent` if drifted
- Direct event injection for testing

- [ ] **Step 6: Run all tests — verify they pass**

Run: `mvn --batch-mode test -pl infra`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```
git add infra/
git commit -m "feat(#1): InfraFaultPolicy + InfraEventSource

InfraFaultPolicy: default fault rules — drift auto-remediates,
transient failures retry, permanent failures remove node.

InfraEventSource: merges periodic poll drift detection with
real-time event streams into a single Multi<StateEvent>."
```

---

## Task 8: casehub-ops-infra — Full Lifecycle Integration Test

**Repo:** `casehub-ops`

**Files:**
- Test: `infra/src/test/java/io/casehub/ops/infra/InfraLifecycleIntegrationTest.java`

- [ ] **Step 1: Write integration test**

```java
package io.casehub.ops.infra;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.api.infra.goal.*;
import io.casehub.ops.api.infra.spi.*;
import io.casehub.ops.api.infra.state.*;
import io.casehub.ops.api.infra.k8s.*;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.infra.standalone.StandaloneBackend;
import io.casehub.ops.infra.standalone.InMemoryResourceProvisioner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InfraLifecycleIntegrationTest {

    @Test
    void fullLifecycle_declare_plan_provision_readState_detectDrift() {
        var mapper = new ObjectMapper();
        var memProvisioner = new InMemoryResourceProvisioner();
        var standaloneBackend = new StandaloneBackend(List.of(memProvisioner));
        var backends = List.<InfraBackend>of(standaloneBackend);
        var compiler = new InfraGoalCompiler();
        var provisioner = new InfraNodeProvisioner(backends);
        var stateAdapter = new InfraActualStateAdapter(backends);

        // 1. DECLARE — compile YAML goals to graph
        var nsConfig = mapper.createObjectNode().put("name", "test-ns");
        var decl = new ResourceDeclaration("ns1", "k8s_namespace", null, nsConfig, List.of());
        var goals = new InfraGoals("standalone", List.of(decl), List.of());
        var graph = compiler.compile(goals, null, null);
        assertThat(graph.nodes()).hasSize(1);

        // 2. PROVISION — provision the node
        var node = graph.nodes().getFirst();
        var ctx = new ProvisionContext(node.id(), "tenant-1", Optional.empty());
        var result = provisioner.provision(node, ctx).await().indefinitely();
        assertThat(result).isInstanceOf(ProvisionResult.Provisioned.class);

        // 3. READ STATE — verify actual state matches
        var actualState = stateAdapter.readActual(graph).await().indefinitely();
        assertThat(actualState.statusOf(NodeId.of("ns1"))).contains(NodeStatus.PRESENT);

        // 4. DETECT DRIFT — no drift expected
        var drift = standaloneBackend.detectDrift(NodeId.of("ns1")).await().indefinitely();
        assertThat(drift.drifted()).isFalse();
    }

    @Test
    void mixedBackend_fallsBackCorrectly() {
        var mapper = new ObjectMapper();
        var memProvisioner = new InMemoryResourceProvisioner();
        var standaloneBackend = new StandaloneBackend(List.of(memProvisioner));
        var compiler = new InfraGoalCompiler();

        var nsConfig = mapper.createObjectNode().put("name", "ns1");
        var deployConfig = mapper.createObjectNode()
            .put("namespace", "ns1").put("name", "app").put("image", "app:1").put("replicas", 2);
        var ns = new ResourceDeclaration("ns1", "k8s_namespace", null, nsConfig, List.of());
        var deploy = new ResourceDeclaration("d1", "k8s_deployment", null, deployConfig, List.of("ns1"));
        var goals = new InfraGoals("standalone", List.of(ns, deploy), List.of());

        var graph = compiler.compile(goals, null, null);
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies()).hasSize(1);

        // Both use standalone backend
        var provisioner = new InfraNodeProvisioner(List.of(standaloneBackend));
        for (var node : graph.nodes()) {
            var ctx = new ProvisionContext(node.id(), "tenant-1", Optional.empty());
            var result = provisioner.provision(node, ctx).await().indefinitely();
            assertThat(result).isInstanceOf(ProvisionResult.Provisioned.class);
        }
    }
}
```

- [ ] **Step 2: Run integration test — verify it passes**

Run: `mvn --batch-mode test -pl infra -Dtest=InfraLifecycleIntegrationTest`
Expected: All tests PASS

- [ ] **Step 3: Run full test suite**

Run: `mvn --batch-mode test`
Expected: All tests across all modules PASS

- [ ] **Step 4: Commit**

```
git add infra/
git commit -m "test(#1): full lifecycle integration test

Validates: declare → compile → provision → read state → detect drift.
Tests mixed-mode graph with dependencies.
Uses StandaloneBackend + InMemoryResourceProvisioner — no external deps."
```

---

## Self-Review Checklist

| Spec section | Task covering it |
|---|---|
| §4.1 InfraNodeSpec sealed hierarchy | Task 2 |
| §4.2 InfraDesiredNodeSpec wrapper | Task 2 |
| §4.3 Supporting domain types | Task 2 |
| §4.4 ResourceState | Task 3 |
| §4.5 DriftReport | Task 3 |
| §4.6 InfraProvisionContext | Task 3 |
| §4.7 InfraGoals | Task 3 |
| §5 InfraBackend SPI | Task 3 |
| §5.1 Backend result types | Task 3 |
| §5.2 Backend routing | Task 4 (compiler), Task 5 (dispatcher) |
| §5.3 StandaloneBackend | Task 6 |
| §6 ResourceProvisioner SPI | Task 3 (SPI), Task 6 (impl) |
| §7 InfraGoalCompiler | Task 4 |
| §8 State management | Task 5 (adapter), Task 6 (backend state) |
| §9 Drift detection | Task 7 (EventSource) |
| §10 Fault policy | Task 7 (FaultPolicy) |
| §11 Plan/apply lifecycle | Task 3 (types), Task 5 (provisioner phase handling) |
| §15.1 Integration test | Task 8 |

**Prerequisite (desiredstate-api):** Task 1

**Not in scope (§15.2-15.3):** TerraformBackend, AnsibleBackend, LlmProvisioner, cloud resource types, remote execution, GenericResourceSpec pass-through, multi-tenant partitioning, scale limits, RBAC.
