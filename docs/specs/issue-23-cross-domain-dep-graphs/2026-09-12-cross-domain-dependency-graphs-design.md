# Cross-Domain Dependency Graphs — Design Spec

**Issue:** casehubio/casehub-ops#23
**Companion:** casehubio/casehub-desiredstate#140 (generic framework)
**Date:** 2026-09-12
**Status:** Draft

## Problem

Each domain module (infra, deployment, compliance, IoT) compiles and reconciles independently. In practice, domains have dependencies:

- Deployment agents need infrastructure provisioned first (K8s namespaces, databases)
- Compliance evidence collection may depend on deployment being in steady state
- IoT device provisioning may depend on infrastructure (network, gateway) being ready

Currently there is no mechanism for expressing or enforcing these cross-domain dependencies. A single domain's `DesiredStateGraph` handles intra-domain ordering via `Dependency` edges, but cross-domain ordering has no representation.

## Existing Infrastructure (Key Finding)

The runtime already supports multi-domain reconciliation — the gap is narrower than it appears:

| SPI | Multi-domain mechanism | Status |
|-----|----------------------|--------|
| `ActualStateAdapter` | `DefaultActualStateAdapterRouter` dispatches by `NodeType` via `handledTypes()` | Already works |
| `NodeProvisioner` | `DefaultNodeProvisionerRouter` dispatches by `NodeType` via `handledTypes()` | Already works |
| `FaultPolicy` | `FaultPolicyEngine` accepts `List<FaultPolicy>`, evaluates all | Already works |
| `EventSource` | `DefaultMergedEventSource` merges `Collection<EventSource>` streams | Already works |
| `GoalCompiler` | `@DesiredStateQualifier` (#110) + new `DomainRegistration` SPI | Needs orchestration layer |

The `DesiredStateGraph` interface has `overlay()` (merge nodes) and `connect()` (weak interleaving — other's roots before this's leaves). A new `sequenceAfter()` method provides strict domain ordering: `downstream.sequenceAfter(upstream)` creates `Dependency(downstreamRoot, upstreamLeaf)` for each pair — all upstream leaf nodes must be provisioned before any downstream root node starts. `ReconciliationLoop.reconcileTypes()` provides per-type-group resync intervals within a single loop. `ImmutableDesiredStateGraph.withDependency()` has cycle detection via `CyclicDependencyException`.

**The single-domain-per-classpath constraint (ARC42STORIES §2) is lifted.** Four of five SPIs already support multiple implementations via existing routing and collection injection. Only GoalCompiler lacked routing, resolved by `@DesiredStateQualifier` (#110) and the new `DomainRegistration` SPI.

## Architecture

### Primary: Flat Merge (Single-Process)

The orchestrator discovers all `DomainRegistration` beans, calls each domain's `compile()` method, and merges the per-domain graphs into a single `DesiredStateGraph` using `sequenceAfter()` (strict domain ordering), `overlay()` (node merging), and `withDependency()` (fine-grained edges). The merged graph feeds one `ReconciliationLoop` per tenant.

```
Orchestrator
  ├── InfraDomainRegistration.compile() → infra graph (SingleGraph)
  ├── DeploymentDomainRegistration.compile() → deployment graph (SingleGraph)
  ├── ComplianceDomainRegistration.compile() → compliance graph (SingleGraph)
  └── IoTDomainRegistration.compile() → iot graph (SingleGraph)
                    │
                    ▼
          deployment.sequenceAfter(infra)         // all infra done → deployment starts
          compliance.sequenceAfter(deployment)    // all deployment done → compliance starts
          iot.overlay(infra) + fine-grained edges  // iot parallel with deployment
                    │
                    ▼
          ReconciliationLoop.start(tenancyId, mergedGraph)
                    │
    ┌───────────────┼───────────────┐
    ▼               ▼               ▼
  ActualStateAdapterRouter    NodeProvisionerRouter    FaultPolicyEngine
  (dispatches by NodeType)    (dispatches by NodeType)  (evaluates all)
```

The existing runtime handles everything below the merge point. No new loop type, no new router.

### Compilation Result Constraint

The `DomainOrchestrator` requires all domain compilers to return `CompilationResult.SingleGraph`. If a domain's compiler returns `CompilationResult.Lifecycle`, the orchestrator throws a compile-time error.

**Rationale:** `Lifecycle` compilation results represent intra-domain phased sequencing (e.g., multi-tier topologies with data→application→web phases). Each phase has its own `DesiredStateGraph` and `CompletionCondition`, managed by `LifecycleManager` which advances phases via `ReconciliationListener` callbacks across reconciliation cycles. The `overlay()`/`sequenceAfter()`/`withDependency()` merge operations work on `DesiredStateGraph`, not `CompilationResult` — there is no defined composition of lifecycle phases across domains.

For domains that need phased sequencing AND multi-domain composition, the phased structure should be expressed as graph-level dependencies within a single `DesiredStateGraph` — modelling the phase transitions as explicit dependency edges between node groups. The `Lifecycle` convenience pattern exists for single-domain deployment; cross-domain composition requires the more explicit single-graph representation.

**Future extension:** Cross-domain lifecycle composition (phase alignment, cross-domain completion conditions) is deferred. See desiredstate#TBD.

### Extension: Hierarchical (Multi-Process, Deferred)

For future multi-process deployment, a meta-graph treats each domain as a `DesiredNode` of type `domain`. A `DomainProvisioner` handles domain nodes by managing per-domain inner `ReconciliationLoop` instances (local or remote). The meta-graph feeds a standard outer `ReconciliationLoop`.

This extension wraps the flat merge model — the inner loops are standard `ReconciliationLoop` instances running per-domain graphs. The hierarchical model adds lifecycle management (start/stop/restart), fault propagation (inner→outer), and event bridging. Specification is deferred until a real multi-process use case appears.

## Cross-Domain Dependencies

### Mechanism: Graph Edges (Unified)

Cross-domain dependencies use the same `Dependency` record as intra-domain dependencies. Two usage patterns, one mechanism:

**Coarse ordering (domain-to-domain):** The orchestrator calls `downstream.sequenceAfter(upstream)`, a new method on `DesiredStateGraph`. This computes `this.roots()` and `upstream.leaves()` on the **original** graph instances (before the internal `overlay()` call), then creates `Dependency(downstreamRoot, upstreamLeaf)` for each (root, leaf) pair — meaning every downstream root depends on every upstream leaf. Computing roots/leaves on the original graphs is critical: if computed after `overlay()`, the merged roots/leaves would include nodes from both domains, producing spurious self-dependencies and potential cycles. This follows the same pattern as the existing `connect()` method. The runtime guarantees all upstream leaf nodes are provisioned before any downstream root node starts. Note: `sequenceAfter()` adds O(R×L) edges (downstream roots × upstream leaves) — for large domains with many root/leaf nodes, fine-grained edges are more precise and avoid the combinatorial edge count.

The existing `connect()` method has different semantics: `this.connect(other)` creates `Dependency(thisLeaf, otherRoot)` — other's roots are provisioned before this's leaves, but this's roots can start immediately alongside other. This "weak interleaving" is available for use cases that don't require strict sequencing.

**Fine-grained edges (node-to-node):** Specific cross-domain edges via `DesiredStateGraph.withDependency(new Dependency(dependentNode, prerequisiteNode))`. These target individual nodes using the export mechanism (see below).

`TransitionPlanner` performs a topological sort across the merged graph, respecting both coarse and fine-grained edges. `ImmutableDesiredStateGraph.withDependency()` detects cycles at graph build time via `CyclicDependencyException` — contradictions between fine-grained edges and coarse ordering are caught immediately.

**Per-cycle ordering vs. cross-cycle conditions:** Graph-based ordering controls provisioning order within a single transition plan — it is per-cycle. If a downstream node needs to wait for a condition that takes multiple cycles to stabilise (e.g., DNS propagation after namespace creation), the domain should model that as a separate readiness node with its own `ActualStateAdapter` that returns `ABSENT` until the condition is met. The graph then naturally blocks dependent provisioning until the readiness node reports `PRESENT`.

**Relationship to desiredstate#140 health gates:** The issue requests "health gates — domain X reaches steady state before domain Y starts." The readiness-node pattern subsumes health gates with a more precise mechanism: instead of waiting for an undefined aggregate "domain stable" condition, downstream domains depend on specific upstream readiness nodes. This is architecturally better because (1) the condition is precise and observable per-resource, (2) it uses the existing `ActualStateAdapter` mechanism with no new cross-cycle polling infrastructure, (3) each readiness node participates in the graph as a first-class dependency, and (4) adding a readiness export is a domain-local change. Coarse `sequenceAfter()` ordering handles per-cycle provisioning sequence; readiness nodes handle cross-cycle stabilisation — together they satisfy the #140 requirement.

### Node Export Mechanism

Domains declare stable export identifiers for nodes that downstream domains may depend on. The export is an alias — NOT the internal `NodeId`. This preserves domain isolation: `infra:namespace-ready` remains stable even if the underlying node is renamed.

**YAML DSL declaration:**
```yaml
# casehub-infra.yaml
nodes:
  - id: k8s-ns-prod
    type: k8s-namespace
    export: namespace-ready    # stable name for cross-domain reference
    spec:
      name: production
      labels: { managed-by: casehub-ops }
```

**Annotation-based declaration (new `export` attribute):**

The current `@DeclareNode` annotation has three attributes: `namespace()`, `name()`, and `id()`. A new `export()` attribute is needed, along with annotation processor changes to collect exports and wire them into the generated `DomainRegistration`.

```java
// New attribute added to @DeclareNode:
@DeclareNode(id = "k8s-ns-prod", export = "namespace-ready")
```

**Programmatic registration:**
```java
public class InfraDomainRegistration implements DomainRegistration {
    private final InfraGoalCompiler compiler;

    public String domainId() { return "infra"; }

    public CompilationResult compile(String goalsPath, DesiredStateGraphFactory factory) {
        InfraGoals goals = InfraGoals.load(goalsPath);  // domain-specific type, hidden from orchestrator
        return compiler.compile(goals, factory);
    }

    public Map<String, NodeId> exportedNodes() {
        return Map.of("namespace-ready", NodeId.of("k8s-ns-prod"));
    }
}
```

Downstream domains reference exports as `domain:export-name`:
```yaml
# casehub-orchestration domains block
domains:
  infra: {}
  deployment:
    depends-on: [infra]
    edges:
      - from: agent-bootstrap
        to: infra:namespace-ready
```

Adding an export is a domain-local change. Removing one is a breaking change for downstream domains (analogous to removing a public API method).

**Export validation (fail-fast):** `ImmutableDesiredStateGraph.withDependency()` silently returns `this` when either node doesn't exist in the graph. This means stale exports would silently drop cross-domain edges — defeating the purpose of the dependency declaration. The `DomainOrchestrator` must validate exports after compilation:

1. For each `DomainRegistration`, every `NodeId` returned by `exportedNodes()` must exist in the `SingleGraph` produced by `compile()`. If not, throw `InvalidExportException` at orchestration time — the export map is stale.
2. For each `edges` entry in the orchestration YAML, the resolved `NodeId` (from the upstream domain's exports) must exist in the merged graph. If not, throw `UnresolvableEdgeException`.
3. For each `depends-on` entry, validate both domains' graphs are non-empty before calling `sequenceAfter()`.

This validation runs before any `withDependency()` calls, ensuring all edge targets are valid.

### Cross-Domain Fault Recovery

In the merged graph, a fault in an upstream node (e.g., infra namespace deletion) can cascade to downstream nodes (e.g., deployment agents become unreachable). The existing runtime handles this without cross-domain fault policy:

1. **Detection:** Each domain's `ActualStateAdapter` independently detects its own nodes' state. When infra's adapter detects the namespace is ABSENT, and deployment's adapter detects agents are unreachable, both report to the unified `ActualState`.

2. **Fault response:** `FaultPolicyEngine` evaluates all registered `FaultPolicy` implementations. Each domain's fault policy handles its own domain's faults — `InfraFaultPolicy` produces mutations for infra nodes, deployment's fault policy for deployment nodes. No policy needs to reason about other domains' node types.

3. **Recovery ordering:** The merged graph's `Dependency` edges ensure correct re-provisioning order. When `TransitionPlanner` plans the recovery, infra nodes (prerequisites) are ordered before deployment nodes (dependents) in the topological sort.

4. **Convergence:** The reconciliation loop's periodic cycling ensures recovery converges — infra re-provisions first, deployment re-provisions next cycle (or within the same cycle if both need provisioning, ordered by dependency edges).

No explicit cross-domain fault policy is needed. Domain-specific fault policies handle detection and mutation; the merged graph's dependency structure handles recovery ordering.

### NodeId Collision Prevention

Domain modules must ensure their `NodeId` sets are disjoint — analogous to the disjoint `handledTypes()` requirement for routers. `ImmutableDesiredStateGraph.overlay()` enforces this with a fail-fast check: it throws `IllegalArgumentException` if two graphs contain the same `NodeId` with differing `DesiredNode` values. Since `sequenceAfter()` calls `overlay()` internally, all graph merge operations inherit this collision detection.

In practice, `NodeId` values naturally partition by domain because each domain uses domain-specific naming conventions (e.g., `k8s-ns-prod`, `agent-alpha`, `soc2-encryption`, `iot-gateway-01`). The export mechanism provides stable cross-domain references via aliases, so downstream domains never need to know or duplicate upstream `NodeId` values.

If automatic namespacing is needed in the future (e.g., `domainId + ":" + nodeId` prefix applied by the orchestrator during merge), it can be added to `DomainOrchestrator` without changing the `DomainRegistration` SPI. See desiredstate#TBD.

## Declaration Model

Cross-domain orchestration extends the existing desiredstate YAML DSL with a top-level `domains:` block:

```yaml
domains:
  infra:
    goals: casehub-infra.yaml

  deployment:
    goals: casehub-deployment.yaml
    depends-on: [infra]              # coarse: deployment.sequenceAfter(infra)

  compliance:
    goals: casehub-compliance.yaml
    depends-on: [deployment]         # coarse: compliance.sequenceAfter(deployment)
    edges:                           # fine-grained: specific node dependencies
      - from: soc2-encryption
        to: infra:namespace-ready

  iot:
    goals: casehub-iot.yaml
    edges:
      - from: gateway-config
        to: infra:namespace-ready
```

Domain YAML files remain independent — they don't reference other domains. The `domains:` block is the only place that names multiple domains and declares cross-domain relationships.

**Parsing:** The `DomainOrchestrator` (in `casehub-desiredstate-runtime`) parses the `domains:` YAML block. It validates the structure (domain identifiers, `depends-on` references, `edges` entries, `goals` paths), resolves domain identifiers against discovered `DomainRegistration` beans, and invokes each domain's `compile()` method with the specified goals path. The orchestration YAML is a runtime concern parsed by the orchestrator, not a DSL compilation concern — no separate YAML module parser is needed.

## DomainRegistration SPI

Each domain provides a `DomainRegistration` bean — the orchestrator's discovery mechanism:

```java
public interface DomainRegistration {
    String domainId();
    CompilationResult compile(String goalsPath, DesiredStateGraphFactory factory);
    Map<String, NodeId> exportedNodes();
}
```

**What it provides:**
- Domain identity (`domainId()`)
- Compilation facade (`compile()` — loads domain-specific goals and compiles them internally, hiding the goals type `G`; the orchestrator never handles the raw goals object, eliminating the `Object`-typed intermediary and the runtime cast that would follow)
- Exported node identifiers (`exportedNodes()`)

**What it does NOT provide:** references to `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, or `EventSource`. These are already independently routed by existing runtime infrastructure — the routers discover them via CDI `Instance<T>` and dispatch by `NodeType`.

## Multi-Tenancy Interaction

In the flat merge model, each tenant loop reconciles one merged graph spanning all domains. The existing composite key scheme (`tenancyId:appId:clusterId`) continues to identify loop instances. Each application deployment creates one merged graph per cluster, containing nodes from all domain modules.

Type-filtered resync (`ReconciliationLoop.reconcileTypes()`) provides per-domain-type resync intervals within the single loop — infra nodes can resync every 5 minutes while compliance evidence resyncs hourly, within the same `TenantLoop`.

**Limitation:** All domain nodes share one fault feedback cycle. A slow domain provisioner (e.g., a Terraform apply taking minutes) delays the entire reconciliation cycle for that tenant. Type-filtered resync mitigates this — each type group has its own timer, so slow infra provisioning doesn't block a compliance resync scheduled on a different interval group.

**Cross-domain edges during type-filtered resync:** When `reconcileTypes()` filters the desired graph by `NodeType`, nodes of other types — and all dependency edges involving them — are removed by `filterByTypes()` (which calls `withoutNode()`, stripping all edges referencing removed nodes). Cross-domain dependency edges are therefore invisible during type-filtered resync. This is correct behaviour: type-filtered resync reconciles one type group independently, and `TransitionPlanner` only provisions nodes whose actual state is ABSENT or DRIFTED. If a cross-domain prerequisite is missing (e.g., a deployment node's infra namespace was deleted), the deployment provisioner will fail; the infra type group's own resync cycle re-provisions the namespace, and the deployment node succeeds on its next cycle. Cross-domain ordering is established by the initial full reconciliation cycle; type-filtered resyncs provide eventual consistency per type group.

## Ops-Side Wiring (casehub-ops)

Each ops domain module provides a `DomainRegistration`:

| Module | domainId | Key exports |
|--------|----------|-------------|
| `infra/` | `infra` | `namespace-ready`, `cluster-ready` |
| `deployment/` | `deployment` | `topology-ready` |
| `compliance/` | `compliance` | (typically no exports — leaf domain) |
| `iot/` | `iot` | (typically no exports — leaf domain) |

The `app/` module provides the orchestration YAML declaring the ordering:

```yaml
# casehub-orchestration.yaml (in app/)
domains:
  infra: {}
  deployment:
    depends-on: [infra]
  compliance:
    depends-on: [deployment]
  iot:
    edges:
      - from: gateway-config
        to: infra:namespace-ready
```

### ARC42STORIES §2 Update

The single-domain-per-classpath constraint is replaced with:

> Multi-domain deployment: multiple domain modules may coexist on the same classpath. The desiredstate runtime routes `ActualStateAdapter` and `NodeProvisioner` calls by `NodeType`; `FaultPolicy` and `EventSource` implementations are collected and merged. `GoalCompiler` ambiguity is resolved via `@DesiredStateQualifier` and `DomainRegistration`. Domain modules must ensure their `handledTypes()` sets are disjoint — the routers throw on duplicate `NodeType` claims.

## Implementation Layers

### Layer 1: casehub-desiredstate (desiredstate#140)

- `DesiredStateGraph.sequenceAfter()` method — strict sequencing (downstream roots depend on upstream leaves); new method on the interface and `ImmutableDesiredStateGraph`
- `DomainRegistration` SPI interface in `casehub-desiredstate-api` — `compile()` facade hiding domain-specific goals type
- `DomainOrchestrator` in `casehub-desiredstate-runtime` — discovers registrations, parses `domains:` block, calls `compile()`, merges graphs via `sequenceAfter()`/`overlay()`/`withDependency()`, enforces `SingleGraph` compilation result
- Node export mechanism — new `export` attribute in YAML DSL, new `export()` attribute in `@DeclareNode` annotation (current attributes: `namespace()`, `name()`, `id()`), annotation processor changes to collect exports
- Orchestration YAML parsing — `domains:` block with `depends-on`, `edges`, `goals`; parsed by `DomainOrchestrator`
- Tests: multi-domain compilation, cross-domain edge injection, cycle detection, type-filtered resync with mixed-domain graphs, `Lifecycle` result rejection

### Layer 2: casehub-ops (ops#23 follow-up)

- `InfraDomainRegistration`, `DeploymentDomainRegistration`, `ComplianceDomainRegistration`, `IoTDomainRegistration` — one per domain module
- Export declarations on key nodes in each domain's YAML/compiler
- Orchestration YAML in `app/` declaring domain ordering
- Integration tests: merged graph with all four domains, cross-domain dependency ordering, fault isolation via type-filtered resync
- ARC42STORIES §2 update

## References

- `ReconciliationLoop.java` — existing per-tenant loop with type-filtered resync
- `DefaultActualStateAdapterRouter.java` — NodeType-based routing for adapters
- `DefaultNodeProvisionerRouter.java` — NodeType-based routing for provisioners
- `FaultPolicyEngine.java` — List-based policy evaluation
- `DefaultMergedEventSource.java` — Collection-based event stream merging
- `ImmutableDesiredStateGraph.java` — `overlay()`, `connect()`, `sequenceAfter()` (new), `withDependency()`, cycle detection
- `CompilationResult.java` — `SingleGraph`/`Lifecycle` compilation outputs
- `@DesiredStateQualifier` — GoalCompiler CDI qualification (#110)
- casehubio/casehub-desiredstate#140 — generic framework issue
- casehubio/casehub-ops#23 — this design issue
