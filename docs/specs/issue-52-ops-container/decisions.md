# Decisions — #98 Container EventSource + Fault Detection

## D1: EventSource architecture

**Choice:** Hot emitter base with Podman event stream feed
**Alternatives:**
- Real Podman event stream only — loses emit() API needed by tests and manual callers
- Hot emitter only — defers real-time detection, the primary value of this issue
**Rationale:** The Podman stream has to feed into something, and that something is the emitter. Tests push events via emit(), production connects the /events stream. Unified event flow.
**Trade-offs:** Slightly more complex than a pure emitter — need to manage the stream subscription lifecycle
**Sources:** DeploymentEventSource.java, InfraEventSource.java (existing hot emitter pattern), PodmanClient.java (REST API surface)
**Exploration:** quick
**Status:** captured

## D2: Health check integration

**Choice:** EventSource handles health events from Podman stream
**Alternatives:**
- Separate HealthMonitor class — more isolation but adds another component and a second path to StateEvents
**Rationale:** Health status events from the Podman stream are just another event type translated to StateEvents. Keeps the event flow unified — one stream, one subscriber path.
**Trade-offs:** EventSource becomes slightly more complex than sibling implementations, but health is a core container concern
**Sources:** NodeStatus.java (DRIFTED for unhealthy), PodmanActualStateAdapter.java (already maps stopped → DRIFTED)
**Exploration:** quick
**Status:** captured

## D3: Fault classification strategy

**Choice:** Immediate escalation for permanent faults, threshold-based for transient
**Alternatives:**
- Same threshold for both — delays response to known-permanent failures (no point retrying an image that doesn't exist)
- Separate fault policies — most granular but more complex than needed
**Rationale:** Permanent faults (image pull failure) cannot be fixed by retry. Transient faults (OOM restart) may self-heal. Different escalation speeds match the fault semantics.
**Trade-offs:** Custom FaultPolicy logic beyond ThresholdFaultPolicy — needs a wrapper that routes by fault permanence
**Sources:** ThresholdFaultPolicy.java (tier-based escalation), FaultType.java (NODE_DEGRADED, PROVISION_FAILED), DeploymentFaultPolicy.java (delegate pattern)
**Exploration:** quick
**Status:** captured

## D4: Podman event to NodeId mapping

**Choice:** Name-based NodeId using container name directly
**Alternatives:**
- Graph-aware resolution — more accurate but couples EventSource to graph state and may not be available at subscription time
**Rationale:** PodmanActualStateAdapter already uses container name as NodeId (extractContainerName → NodeId.of). Consistent mapping. Unknown container names are ignored by the reconciliation loop.
**Trade-offs:** Assumes container names match NodeIds — true by construction in the current goal compiler
**Sources:** PodmanActualStateAdapter.java:86-92 (extractContainerName), ContainerGoalCompiler.java
**Exploration:** quick
**Status:** captured
