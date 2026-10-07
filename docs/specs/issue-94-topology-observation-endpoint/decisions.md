# Decisions — #94 Topology Observation Endpoint

## D1: API location

**Choice:** New `OpsTopologyApi` class in the service module with `@McpDomain` at `/api/ops/topology`
**Alternatives:**
- Extend `OpsDeploymentApi` — conflates deployment lifecycle with topology observation
- Deployment module API class — deployment module is a library, `@McpDomain` lives in service
**Rationale:** Topology observation is a distinct concern; separate API class keeps responsibilities clean
**Trade-offs:** One more API class (minimal cost)
**Sources:** `OpsDeploymentApi.java`, `OpsApprovalApi.java` (existing `@McpDomain` pattern)
**Exploration:** quick
**Status:** captured

## D2: Adaptation state access

**Choice:** `TopologySnapshot` record returned by `DeploymentAdaptiveSituationRecompiler.snapshot(tenancyId)`
**Alternatives:**
- Open `TenantAdaptationState` to public — leaks mutable internals
- Event-sourced view — overkill for S-scale feature
**Rationale:** Immutable snapshot preserves encapsulation, one method exposes everything needed
**Trade-offs:** Snapshot is a point-in-time freeze, not a live view (acceptable for REST)
**Sources:** `TenantAdaptationState.java`, `DeploymentAdaptiveSituationRecompiler.java`
**Exploration:** quick
**Status:** captured

## D3: Response shape

**Choice:** Single flat response from `GET /api/ops/topology/{tenancyId}` with four sections: nodes, situations, rules, conflicts
**Alternatives:**
- Nested sub-resources (`/nodes`, `/situations`, `/rules`) — premature splitting for data consumed together
- GraphQL query — schema extension complexity not warranted
**Rationale:** Demos and UI fetch all sections together; one round-trip is sufficient
**Trade-offs:** No selective fetching (can add query params later if needed)
**Sources:** Issue #94 requirements, issue #91 (three-cloud demo consumer)
**Exploration:** quick
**Status:** captured
