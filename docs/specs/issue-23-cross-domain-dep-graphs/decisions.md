## D1: Orchestration layer location

**Choice:** casehub-desiredstate (generic runtime), not casehub-ops
**Alternatives:**
- casehub-ops — closer to first consumer, but locks cross-domain orchestration to ops-specific concerns
- casehub-engine — has case management for human gates, but orchestration is a desired-state concern; engine consumes it
**Rationale:** The routers (ActualStateAdapterRouter, NodeProvisionerRouter) already live in casehub-desiredstate-runtime. Cross-domain composition is the same pattern — the runtime knows how to reconcile a graph, it should compose and sequence domain graphs too. The annotation-based compilation model (`@DeclareNode`/`@DesiredStateGraph`, `CompilationResult.SingleGraph`/`Lifecycle`, `@DesiredStateQualifier`) already supports multi-graph composition — the orchestration layer coordinates these compilers, merges their graphs, and feeds the result to ReconciliationLoop.
**Trade-offs:** Requires a casehub-desiredstate release before casehub-ops can consume the framework. Design must be generic enough for non-ops consumers.
**Sources:** ReconciliationLoop.java (runtime), DefaultActualStateAdapterRouter.java (router pattern), CompilationResult.java (multi-graph support), @DesiredStateQualifier (#110), casehubio/casehub-desiredstate#140
**Exploration:** quick
**Status:** revised (R1-05: added annotation model acknowledgment)

## D2: Deployment model

**Choice:** Single-process flat merge as the primary architecture — multiple domain JARs on one classpath, domain graphs merged via `overlay()`/`connect()` into one ReconciliationLoop. Hierarchical model documented as a future extension for multi-process deployment.
**Alternatives:**
- Hierarchical first (previous choice) — meta-loop with domain-level nodes; elegant but adds DomainProvisioner, inner-loop lifecycle, event bridging — substantial engineering for a topology nobody needs yet
- Multiple-process only — distributed coordination complexity without in-JVM benefit
**Rationale:** The first consumer (casehub-ops) runs all domain modules in a single JVM. The existing runtime already handles mixed-type graphs: `DefaultActualStateAdapterRouter` dispatches by NodeType, `DefaultNodeProvisionerRouter` dispatches by NodeType, `FaultPolicyEngine` evaluates all policies, `DefaultMergedEventSource` merges all event streams, `ReconciliationLoop.reconcileTypes()` provides per-type-group resync intervals. A merged graph reuses all of these. Hierarchical deployment is additive — it wraps the flat merge model with a meta-loop when multi-process isolation is needed, without changing the single-process code.
**Trade-offs:** Defers domain-level fault isolation (entire domain restart) and remote domain processes to the hierarchical extension. For single-process, per-SPI fault handling and type-filtered resync provide adequate isolation.
**Sources:** ReconciliationLoop.java (type-filtered resync), DefaultActualStateAdapterRouter (NodeType routing), DefaultNodeProvisionerRouter (NodeType routing), FaultPolicyEngine (List<FaultPolicy>), DefaultMergedEventSource (Collection<EventSource>)
**Exploration:** quick
**Status:** revised (R1-04: flat merge primary, hierarchical deferred)

## D3: Cross-domain dependency granularity

**Choice:** Fine-grained node-to-node edges as the universal mechanism. Coarse domain-to-domain ordering is syntactic sugar — the orchestration layer calls `DesiredStateGraph.connect()` on domain graphs in the declared order, which adds edges from all leaves of the upstream domain to all roots of the downstream domain. No separate "domain-to-domain dependency" type.
**Alternatives:**
- Dual mechanism (previous choice) — both coarse and fine-grained as separate types; adds a second mechanism that must be kept consistent with graph edges
- Domain-to-domain only — simpler but can't express "this specific agent needs this specific namespace"
- Node-to-node only without coarse sugar — most expressive but verbose for common patterns
**Rationale:** `DesiredStateGraph` already has `withDependency()` for fine-grained edges and `connect()` for leaf→root edges. These are two usage patterns of one mechanism (the `Dependency` record), not two mechanisms. `ImmutableDesiredStateGraph.withDependency()` has built-in cycle detection via `CyclicDependencyException`, making contradiction prevention automatic — fine-grained edges that violate coarse ordering create cycles and throw at graph build time.
**Trade-offs:** `connect()` adds O(L×R) edges (leaves × roots); for large domains this could be many edges. If a specific edge pattern is needed, fine-grained is more precise.
**Sources:** ImmutableDesiredStateGraph.connect() (leaf→root edges), ImmutableDesiredStateGraph.withDependency() (cycle detection), CyclicDependencyException, Dependency record
**Exploration:** quick
**Depends on:** D1 (orchestration location), D2 (deployment model)
**Status:** revised (R1-03: single mechanism with coarse sugar)

## D4: Cross-domain ordering mechanism

**Choice:** Cross-domain dependency edges (fine-grained or coarse via `connect()`) as the primary ordering mechanism. The TransitionPlanner's topological sort respects these edges. No sentinel concept — domain ordering is expressed in the graph, not as a polling condition.
**Alternatives:**
- Sentinel nodes (previous choice) — domain declares specific nodes as readiness sentinels; fragile (sentinel selection is a design-time decision that can be wrong), semantically incomplete (PRESENT ≠ operationally READY), and redundant (the graph already models ordering)
- All nodes PRESENT — strictest, blocks on any faulted node
- Configurable per-edge — most flexible but complex
**Rationale:** If deployment-agent depends on infra-namespace, the TransitionPlanner orders namespace provisioning before agent provisioning in the topological sort. Coarse ordering (`connect()`) adds edges from all leaves of domain A to all roots of domain B — "don't start any deployment until all infra leaf nodes are provisioned." This reuses existing graph semantics: `withDependency()` for precision, `connect()` for convenience. No new concept needed.
**Trade-offs:** Graph-based ordering is per-cycle (provisioning order within a transition plan), not a cross-cycle health gate. If a persistent readiness check is needed (wait for DNS propagation after namespace creation), the domain should model that as a separate node with its own ActualStateAdapter (the adapter returns ABSENT until ready).
**Sources:** TransitionPlanner (topological sort), ImmutableDesiredStateGraph.connect() (coarse ordering), ImmutableDesiredStateGraph.withDependency() (fine-grained ordering)
**Exploration:** quick
**Depends on:** D3 (dependency granularity)
**Status:** revised (R1-02: graph-based ordering replaces sentinels)

## D5: Cross-domain dependency declaration model

**Choice:** Extend the existing desiredstate YAML DSL with a top-level `domains:` block for cross-domain declarations. Domain YAML files remain independent modules. The orchestration is expressed within the DSL's existing module/import system rather than a separate `casehub-orchestration.yaml`.
**Alternatives:**
- Separate orchestration YAML (previous choice) — adds a new file format, fragments the YAML story; operators need two different YAML schemas
- Inline in domain YAML — self-describing but couples domains to each other's node IDs
- Code-level wiring — most flexible but not declarative
**Rationale:** The YAML DSL already has modules, imports, `forEach`, lifecycle phases, rules, and invariants. Cross-domain ordering and edges are graph composition — the DSL already models graph structure. A `domains:` block declares ordering and cross-domain edges using stable exported node identifiers rather than internal node IDs, preserving domain isolation.

**Node export mechanism:** Domains declare exports at the node level, consumed at the domain level via `domain:export-name` references. Each compilation path has a native declaration syntax:
- **YAML DSL:** `export: <stable-name>` attribute on a node declaration. The DSL compiler collects exports per domain.
- **Annotation-based:** `@DeclareNode(type = "...", export = "stable-name")`. The annotation processor collects exports into the generated GoalCompiler or DomainRegistration.
- **Programmatic GoalCompiler:** `DomainRegistration.exportedNodes()` returns `Map<String, NodeId>` mapping stable export names to internal NodeIds.

The export identifier is an alias — NOT the internal NodeId. `infra:namespace-ready` remains stable even if the underlying node is renamed from `k8s-ns-prod` to `k8s-namespace-production`. Downstream domains reference `infra:namespace-ready`, never the internal ID.

**Trade-offs:** Cross-domain node references create soft coupling to exported identifiers. Domains must declare export identifiers for nodes that downstream domains depend on. Adding an export is a domain-local change; removing one is a breaking change for downstream domains (analogous to removing a public API method).
**Sources:** casehub-desiredstate-yaml module (modules, imports, rules, invariants), existing per-domain YAML pattern (casehub-deployment.yaml, casehub-compliance.yaml, casehub-infra.yaml)
**Exploration:** quick
**Depends on:** D3 (dependency granularity), D4 (ordering mechanism)
**Status:** revised (R1-06: extend existing DSL; R2-01: export mechanism specified)

## D6: Domain identity and orchestration discovery

**Choice:** DomainRegistration SPI — each domain provides a single `DomainRegistration` bean that declares domain identity (name/namespace), references its GoalCompiler (via `@DesiredStateQualifier`), and provides goal loading strategy. The orchestrator discovers registrations via CDI `Instance<DomainRegistration>`. The registration does NOT reference ActualStateAdapter, NodeProvisioner, FaultPolicy, or EventSource — these are already independently routed by the existing runtime infrastructure.
**Alternatives:**
- Full DomainDescriptor (previous choice) — bundles all five SPIs into one descriptor; over-scoped because 4/5 SPIs are already routed by `DefaultActualStateAdapterRouter` (by NodeType), `DefaultNodeProvisionerRouter` (by NodeType), `FaultPolicyEngine` (List collection), and `DefaultMergedEventSource` (Collection merge)
- `@Domain("infra")` CDI qualifier on each SPI bean — standard CDI but scatters metadata; doesn't address goal loading
- No registration, pure CDI discovery — `@DesiredStateQualifier` resolves GoalCompiler ambiguity, but the orchestration layer still needs domain identity and goal loading
**Rationale:** The orchestration layer needs three things: what domains exist (identity), how to compile each domain's goals (compiler reference), and where to find goals (loading strategy). It does NOT need references to the other four SPIs — those are already discovered and routed by existing infrastructure. This is narrower than the original DomainDescriptor but still necessary for orchestration-level discovery.
**Trade-offs:** Adds a new SPI interface to casehub-desiredstate-api. Each domain module needs one registration bean. More CDI-native than bundling all SPIs — the registration focuses on what the orchestration layer uniquely needs.
**Sources:** @DesiredStateQualifier (#110, landed), DefaultActualStateAdapterRouter (NodeType routing for adapters), DefaultNodeProvisionerRouter (NodeType routing for provisioners), FaultPolicyEngine (List injection for policies), DefaultMergedEventSource (Collection injection for events), InfraBackend.backendId() (identity pattern)
**Exploration:** quick
**Depends on:** D1 (orchestration location)
**Status:** revised (R1-01: narrowed from DomainDescriptor to DomainRegistration)

## D7: Scope and execution model

**Choice:** Full design spec covering both generic framework (casehub-desiredstate) and ops wiring. Single .plan with cross-repo issue queue — desiredstate#140 issues first (framework), then ops implementation issues. User switches repos as the queue advances.
**Alternatives:**
- Design-only branch, separate implementation issues — keeps this branch smaller but loses the unified queue tracking
- Ops-only scope — defers framework design to desiredstate, but the design needs to be coherent across both
**Rationale:** The design is one coherent thing spanning two repos. A unified .plan tracks the full sequence. Cross-repo queue flow is an established pattern.
**Trade-offs:** Design spec covers work in another repo — must be clear about what lives where. .plan spans repos which requires switching context.
**Sources:** work-slot cross-repo pattern, .plan queue model
**Exploration:** quick
**Depends on:** D1 (orchestration location)
**Status:** captured

## D8: Architectural approach — meta-graph with domain nodes (hierarchical extension)

**Choice:** Meta-Graph with Domain Nodes — for hierarchical deployment, the orchestrator creates a meta-graph where each domain is a DesiredNode of type `domain`. A DomainProvisioner handles domain nodes by calling the domain's GoalCompiler, managing a per-domain inner ReconciliationLoop, and monitoring domain health. The meta-graph feeds a standard outer ReconciliationLoop. TransitionPlanner respects coarse dependency edges between domain nodes.
**Alternatives:**
- Single merged graph with domain phases — simpler (one loop, one graph) but sacrifices domain-level fault isolation, independent resync intervals, and multi-process support. Now the PRIMARY architecture per revised D2.
**Rationale:** Reuses existing ReconciliationLoop at both levels — no new loop type needed. Cleanly supports multi-process deployment (inner loops as remote services) and domain-level fault isolation (entire domain restart). The meta-graph concept is architecturally sound, but lifecycle management (inner-loop start/stop/restart, fault propagation across layers, event bridging) requires further specification when a real multi-process use case appears.
**Trade-offs:** Two layers of loops adds complexity. Inner-loop lifecycle management (start/stop/restart, fault escalation, event bridging from inner to outer) is substantial new surface area — underspecified until needed. The flat merge model (D2 primary) avoids all of this for single-process deployment.
**Sources:** ReconciliationLoop.java (reused for both layers), TransitionPlanner (dependency ordering), DesiredNode/NodeType (domain nodes)
**Exploration:** deep-analysis
**Depends on:** D2 (deployment model — applies to hierarchical extension only)
**Status:** revised (R1-04/R1-07: conditioned on hierarchical deployment; flat merge is primary per D2)

## D9: Single-domain constraint evolution

**Choice:** The single-domain-per-classpath constraint (ARC42STORIES.MD §2) is explicitly lifted for multi-domain deployment. The mechanism: each SPI type has an existing multi-domain resolution path.
**Alternatives:**
- Keep constraint, separate processes per domain — adds distributed coordination complexity
- Qualifier-based disambiguation for all five SPIs — heavyweight, unnecessary given existing collection/routing mechanisms
**Rationale:** The five SPI types already have multi-domain resolution:
| SPI | Mechanism | Multi-domain? |
|-----|-----------|---------------|
| ActualStateAdapter | `DefaultActualStateAdapterRouter` routes by `NodeType` via `handledTypes()` | Yes |
| NodeProvisioner | `DefaultNodeProvisionerRouter` routes by `NodeType` via `handledTypes()` | Yes |
| FaultPolicy | `FaultPolicyEngine` accepts `List<FaultPolicy>`, evaluates all | Yes |
| EventSource | `DefaultMergedEventSource` merges `Collection<EventSource>` streams | Yes |
| GoalCompiler | `@DesiredStateQualifier` (#110, landed) + `DomainRegistration` (D6) | Yes (with new SPI) |

The constraint was never a CDI limitation for 4/5 SPIs — the routers and collection injection already handled multiple implementations. Only GoalCompiler lacked routing, resolved by #110 + D6.
**Trade-offs:** Domain modules must ensure their `handledTypes()` don't overlap (the routers throw on duplicate NodeType claims). Domain-specific FaultPolicy and EventSource implementations coexist via list/collection injection without routing — all policies evaluate, all events merge.
**Sources:** DefaultActualStateAdapterRouter (constructor throws on NodeType collision), DefaultNodeProvisionerRouter (same), FaultPolicyEngine (List injection), DefaultMergedEventSource (Collection injection), @DesiredStateQualifier (#110)
**Exploration:** quick (surfaced by review)
**Status:** captured

## D10: Multi-tenancy × domain boundary interaction

**Choice:** In the flat merge model (D2 primary), each tenant loop reconciles one merged graph spanning all domains. The existing composite key scheme (`tenancyId:appId:clusterId`) continues to identify loop instances. Each application deployment creates one merged graph per cluster, containing nodes from all domain modules.
**Alternatives:**
- Per-domain loops per tenant — each domain gets its own TenantLoop; N loops per tenant instead of 1. Adds N×(loop overhead) and requires cross-loop coordination.
- Per-tenant meta-loop (hierarchical) — each tenant gets one meta-loop with N inner loops per domain. Deferred to D8 hierarchical extension.
**Rationale:** The flat merge model keeps the existing per-tenant TenantLoop model unchanged. One loop per composite key reconciles a merged graph. Type-filtered resync (`ReconciliationLoop.reconcileTypes()`) provides per-domain-type resync intervals within the single loop. No scaling concern — the loop count is proportional to (tenants × apps × clusters), unchanged from the current model.
**Trade-offs:** All domain nodes share one fault feedback cycle. A slow domain provisioner delays the entire cycle for that tenant. Per-domain resync intervals mitigate this via type-filtered reconciliation.
**Sources:** ReconciliationLoop.TenantLoop (per-tenant), ApplicationLifecycleService (composite key scheme), ReconciliationLoop.reconcileTypes() (type-filtered resync)
**Exploration:** quick (surfaced by review)
**Status:** captured
