# Topology Observation REST Endpoint — Design

**Issue:** casehubio/casehub-ops#94
**Branch:** issue-94-topology-observation-endpoint
**Date:** 2026-10-07

## Overview

A read-only REST endpoint that exposes the current desired-state topology, actual node statuses, active adaptation situations, and conflict state for a given tenancy. Enables demos, development, and eventually the Ops Centre UI to observe topology changes without database access.

## Endpoint

```
GET /api/ops/topology/{tenancyId}
```

Returns a `TopologyView` record with four sections.

## Response Shape

```java
record TopologyView(
    List<NodeView> nodes,
    List<SituationView> situations,
    List<RuleView> rules,
    List<ConflictView> conflicts
)

record NodeView(
    String nodeId,
    String nodeType,
    String status,       // PRESENT, ABSENT, DRIFTED, UNKNOWN, SUSPENDED, or DESIRED_ONLY
    boolean adapted,     // true if this node was added/modified by an adaptation rule
    String adaptedBy     // rule name, null if not adapted
)

record SituationView(
    String situationId,
    double confidence,
    Instant lastSignal
)

record RuleView(
    String name,
    String triggerSituation,
    boolean active,
    List<String> targetNodeIds
)

record ConflictView(
    String ruleName,
    String nodeId
)
```

### `status` values

- `PRESENT` / `ABSENT` / `DRIFTED` / `UNKNOWN` / `SUSPENDED` — from `ActualState`
- `DESIRED_ONLY` — node exists in the desired graph but has no actual state entry (not yet provisioned)

### `adapted` flag

A node is `adapted: true` when it exists in the adapted graph but not in the base (unadapted) graph, or when its spec differs between base and adapted. The `adaptedBy` field names the rule that introduced or modified it.

## Architecture

### Snapshot method on `DeploymentAdaptiveSituationRecompiler`

```java
public TopologySnapshot snapshot(String tenancyId) { ... }
```

Returns an immutable `TopologySnapshot` record:

```java
public record TopologySnapshot(
    DesiredStateGraph baseGraph,
    DesiredStateGraph adaptedGraph,
    ActualState actualState,
    Map<String, ActiveSituation> trackedSituations,
    Map<String, Boolean> activeRules,
    List<AdaptationRule> rules
)
```

The `snapshot()` method compiles the base graph from goals, recomputes the adapted graph from currently active situations, and captures actual state. All returned data is immutable — safe for concurrent reads.

The `ActualState` must come from the desiredstate runtime. The recompiler receives it in `recompile()` but doesn't store it. Two options:
1. Cache the last-seen `ActualState` in `TenantAdaptationState`
2. Inject the `ActualStateAdapter` and query it at snapshot time

Option 2 is correct — actual state should be fresh at query time, not stale from the last reconciliation cycle. The `snapshot()` method will inject `ActualStateAdapter` to get current actual state.

### `OpsTopologyApi` in the service module

```java
@McpDomain(value = "ops/topology", app = "ops",
           basePath = "/api/ops/topology",
           summary = "Topology observation — desired state, actual state, adaptations")
@ApplicationScoped
public class OpsTopologyApi {

    @Inject DeploymentAdaptiveSituationRecompiler recompiler;

    @PlatformQuery("Get current topology state")
    @RestPath("/{tenancyId}")
    public TopologyView getTopology(@PathParam String tenancyId) { ... }
}
```

Transforms the `TopologySnapshot` into `TopologyView` for REST serialization.

### Conflict detection

The `ConflictView` section is derived from the snapshot by comparing target node sets across active rules — the same check that fires the Micrometer counter in `recompile()`, applied to the snapshot state. This avoids coupling to the counter itself.

## Module changes

| Module | Change |
|--------|--------|
| `deployment` | `TopologySnapshot` record, `snapshot()` on recompiler, expose `targetNodeIds()` for conflict detection |
| `service` | `OpsTopologyApi`, `TopologyView` + sub-records in `rest/dto/` |
| `app` | Test: `TopologyResourceTest` — verify endpoint returns expected shape |

## Testing

1. **Unit test** (`deployment`): `snapshot()` returns correct state for registered tenancy, empty for unknown
2. **Integration test** (`app`): `GET /api/ops/topology/{tenancyId}` returns 200 with expected JSON shape, nodes have correct status and adapted flags
3. **Edge cases**: unregistered tenancy returns empty topology (not 404 — absence of data, not an error)

## References

- `DeploymentAdaptiveSituationRecompiler.java` — holds per-tenant adaptation state
- `TenantAdaptationState.java` — tracked situations, active rules
- `ActualState.java` / `NodeStatus.java` — actual state model
- `OpsApprovalApi.java` — `@McpDomain` pattern reference
- Issue #91 — three-cloud demo (primary consumer)
- Issue #93 — conflict detection metrics (complementary observability)
