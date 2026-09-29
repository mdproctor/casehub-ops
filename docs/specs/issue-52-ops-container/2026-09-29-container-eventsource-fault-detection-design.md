# Container EventSource + Fault Detection

**Issue:** casehubio/casehub-ops#98
**Branch:** issue-52-ops-container
**Date:** 2026-09-29
**Revised:** 2026-09-29 (post decision review — domain/app boundary alignment)

## Overview

Adds container domain SPI implementations for EventSource and FaultPolicy to the ops container module, plus a ContainerReviewSpec in the API module. Follows the established domain module pattern: passive hot emitter EventSource, standard ThresholdFaultPolicy with tier-based escalation.

Active Podman event stream subscription (the equivalent of K8sWatchManager) is a separate follow-up issue for the app module.

## Architecture

### ContainerEventSource

`@ApplicationScoped` CDI bean implementing `EventSource`. Passive hot emitter — identical pattern to `DeploymentEventSource` and `InfraEventSource`.

Provides:
- `Multi<StateEvent> stream()` — hot reactive stream, events emitted before subscription are not replayed
- `emit(StateEvent)` — push events into the stream (used by app-module watchers and tests)
- `emitDrift(NodeId)` — convenience for drift-detected events

No direct connection to Podman. Events are fed by external callers — in production, a `PodmanWatchManager` in the app module (follow-up issue). In tests, via `emit()`.

### ContainerFaultPolicy

`@ApplicationScoped` CDI bean implementing `FaultPolicy`. Follows the standard platform pattern used by all existing domain fault policies (deployment, infra, IoT, K8s).

Configuration:
- Fault types: `PROVISION_FAILED`
- Node types: `container:app`, `container:database`
- Ignore types: `container-review` (prevents fault loops on review nodes)
- Tier 3: `FaultPolicy.addReviewNode()` with `ContainerReviewSpec`

After 3 consecutive PROVISION_FAILED faults on the same container node, adds a human review node to the desired state graph.

### ContainerReviewSpec (API module)

New record in `io.casehub.ops.api.container`:

```java
public record ContainerReviewSpec(NodeId faultedNode, String reason) implements NodeSpec {
    public NodeType nodeType() { return NodeType.of("container-review"); }
}
```

Follows the `DeploymentReviewSpec` and `InfraReviewSpec` pattern.

## Component Diagram

```
External caller (PodmanWatchManager — follow-up)
    │
    ▼
ContainerEventSource.emit(StateEvent)
    │
    ▼
Multi<StateEvent> stream()
    │
    ▼
ReconciliationLoop (runtime)
    │
    ├── ActualStateAdapter.readActual() → plan → provision/deprovision
    └── FaultPolicyEngine
            │
            ▼
        ContainerFaultPolicy
            └── PROVISION_FAILED tier(3) → ContainerReviewSpec
                    │
                    ▼
                Human review WorkItem
```

## Files

| File | Module | Purpose |
|------|--------|---------|
| `ContainerEventSource.java` | container | Passive hot emitter EventSource |
| `ContainerFaultPolicy.java` | container | Standard ThresholdFaultPolicy delegate |
| `ContainerReviewSpec.java` | api | Review node spec record |
| `ContainerEventSourceTest.java` | container (test) | Emit + stream tests |
| `ContainerFaultPolicyTest.java` | container (test) | Fault escalation tests |

## Test Plan

### ContainerEventSourceTest

- `emit()` pushes events to the stream
- `emitDrift()` convenience emits DRIFTED with standard detail
- Multiple subscribers each receive every event (broadcast)

### ContainerFaultPolicyTest

- PROVISION_FAILED on app container: no escalation at faults 1-2, review node at fault 3
- Fault on `container-review` node: no mutations (ignore types)
- Unhandled fault types (DEPROVISION_FAILED etc.): no mutations
- Review node has ContainerReviewSpec with correct faultedNode and reason

## Constraints

- Domain module is a passive Jandex library — no external connections, no lifecycle management
- Active event stream subscription lives in the app module (follow-up issue)
- ContainerFaultPolicy only handles `container:app` and `container:database` node types

## Follow-up

- **PodmanWatchManager** (app module): Active Podman /events stream subscription, event translation, feeding ContainerEventSource.emit(). Follows K8sWatchManager pattern. Separate issue.

## Decision Review Findings Incorporated

- R1-02: Moved active streaming to app module (domain/app boundary)
- R1-09/R1-10: Adopted standard tier(3) pattern (platform consistency)
- R1-05: Deferred health → NodeStatus mapping to PodmanWatchManager follow-up
- R1-12: Container label filtering deferred to PodmanWatchManager follow-up

## References

- `DeploymentEventSource.java` — hot emitter pattern reference
- `InfraEventSource.java` — second hot emitter reference
- `DeploymentFaultPolicy.java` — ThresholdFaultPolicy delegate pattern
- `InfraFaultPolicy.java` — second delegate reference
- `ThresholdFaultPolicy.java` — tier-based escalation API
- `DeploymentReviewSpec.java` — review spec record pattern
- `K8sWatchManager` (app module) — active event stream pattern for follow-up
- casehubio/casehub-ops#98 — issue specification
- Decision review: `reviews/casehub-ops/issue-52-container-decision-20260929-175025/`
