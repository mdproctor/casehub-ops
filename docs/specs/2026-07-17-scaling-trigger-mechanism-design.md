# Scaling Trigger Mechanism

**Issue:** #56
**Date:** 2026-07-17
**Status:** Approved

## Problem

The `scaling-event` child case knows how to evaluate, execute, and verify a scaling
request — but nothing writes `.scalingRequired` to the parent application-lifecycle
case's blackboard to trigger it. The execution pipeline is complete; the trigger
mechanism is missing.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Trigger sources | REST API + RAS situation-driven | RAS situations are the platform's established reactive awareness layer. REST handles explicit operator-initiated scaling. |
| Bridge architecture | Event-unified (Approach 2) | Separate rule evaluation from signaling. Internal `ScalingRequestedEvent` CDI event unifies both paths. Three focused classes instead of one conflated class. |
| Target computation | Confidence-proportional formula | Mirrors `ScaleAction` in deployment module. `effective = clamp((confidence - minConfidence) / (1.0 - minConfidence), 0, 1)`, `target = min + (int)((max - min) * effective)`. |
| Rule storage | In `ServiceDefinition` | Scaling rules are a property of the service. No separate entity or REST resource. Self-describing service definitions. |
| Blackboard path | Unified `.scalingRequired` | Spec IS the trigger — same pattern as `.driftDetected`. Eliminates ordering concern between two separate paths. |
| Cooldown enforcement | Evaluator-level, per-service | Cooldown is tracked by the evaluator (RAS path) and checked by `ScalingResource` (REST path). Prevents wasteful child case spawning. Child cases are ephemeral and cannot track cross-invocation state. Per-service key with max-across-rules period protects the physical resource. |
| Multi-rule conflict | Max-wins | When multiple rules match for the same service, the highest `targetReplicas` value is used. Over-provisioning is safer than under-provisioning. |
| Relationship to AdaptiveTopologyManager | Independent systems at different levels | The deployment module's `AdaptiveTopologyManager` adapts desiredstate graph topology (adding/removing agent nodes). This spec's evaluator scales K8s service replicas (infrastructure-level pod count). Same CDI event, same formula, different abstraction levels. See §Architectural Boundary. |
| Manual scaling behaviour | Temporary when rules are active | REST-initiated scaling is overridden by the next situation evaluation cycle. Consistent with `AdaptiveTopologyManager` (recompiles from base, discarding manual changes) and standard autoscaler semantics (K8s HPA, AWS ASG). See §ScalingResource. |

## Data Model

### ScalingRule

New record in `io.casehub.ops.app.model`:

```java
public record ScalingRule(
    String situationId,
    double minConfidence,
    int minReplicas,
    int maxReplicas,
    Duration cooldownPeriod  // nullable — no cooldown if null
) {}
```

### ServiceDefinition

Gains a `List<ScalingRule> scalingRules` field. Default: `List.of()` (no rules = no
auto-scaling). A service can have multiple rules for different situations.

### ScalingRequestedEvent

Internal CDI event in `io.casehub.ops.app.service`:

```java
public record ScalingRequestedEvent(
    UUID appCaseId,
    String applicationId,
    String tenancyId,
    String serviceId,
    int targetReplicas,
    int currentReplicas,
    String reason,
    ScalingPolicy policy
) {}
```

Unified payload regardless of trigger source (RAS or REST).

## Component Architecture

### SituationScalingEvaluator

`@ApplicationScoped` in `io.casehub.ops.app.service`.

Injects `SituationSource` to obtain confidence values. Listens for
`SituationChangeEvent` via `@ObservesAsync`. On each event:

1. Looks up registered applications for the tenant
2. Queries `SituationSource.activeSituations(tenancyId)` to get current
   `List<ActiveSituation>` with confidence values
3. Reads each app's current `servicesJson` from the database via
   an injected `@Transactional` helper method (see §Transactional Context)
4. For each service with `ScalingRule`s:
   a. Matches active situations to rules using `ActiveSituation.situationId()`
   b. For each matching rule, computes `targetReplicas` using
      `ActiveSituation.confidence()` and the confidence-proportional formula
   c. If multiple rules match simultaneously, takes the **maximum** computed
      `targetReplicas` (max-wins — over-provisioning is safer than
      under-provisioning). Constructs a **merged policy** from all matching
      rules: `minReplicas = min(all matching minReplicas)`,
      `maxReplicas = max(all matching maxReplicas)`,
      `cooldownPeriod = max(all matching cooldownPeriods)`. This guarantees
      the max-wins target passes through the child case's clamp (see
      §ScalingPolicy Sourcing)
   d. If no active situations match any rule for the service, targets the base
      replica count (stored at registration time)
   e. Checks cooldown: if `lastScalingTimestamp` for this service is within the
      effective cooldown period (maximum `cooldownPeriod` across all rules for
      the service), suppresses the event
   f. If `targetReplicas != currentReplicas` and not cooling down, fires
      `ScalingRequestedEvent` via CDI async and records `lastScalingTimestamp`

Handles all `ChangeType` values correctly:
- **TRIGGERED**: situation fired or confidence changed — recompute target from
  active situations
- **RESOLVED**: situation cleared — recompute; if no rules match, target reverts
  to base replicas (scale-down)
- **DISCARDED**: situation invalidated — same as RESOLVED

All three types trigger the same evaluation logic: query active situations,
compute target, compare to current. The `ChangeType` is a notification signal;
the evaluator's decision is always based on the current set of active situations,
not the event type.

#### Periodic re-poll

A `ScheduledExecutorService` re-evaluates all registered tenants every 5 minutes
as a safety net for CDI events lost to async delivery failures, Vert.x
backpressure drops, or observer exceptions. On each poll:

1. For each registered tenant, calls `SituationSource.activeSituations(tenancyId)`
2. Runs the same evaluation logic as the event-driven path
3. Fires `ScalingRequestedEvent` only if the computed target differs from current

This mirrors `AdaptiveTopologyManager.pollAllTenants()`, which uses the same
pattern and interval. The evaluator's cooldown mechanism naturally deduplicates
poll-triggered events — if a CDI event already scaled the service within the
cooldown window, the poll produces no new event.

The executor uses a daemon thread (`scaling-evaluator-poll`) and is shut down
via `@PreDestroy`.

#### Thread safety

The CDI event handler and periodic re-poll run on different threads (Vert.x
worker pool vs. daemon scheduler) and can evaluate the same registration
concurrently. The check-then-act sequence (check cooldown → compute target →
compare current → fire event → record timestamp) must be atomic per
registration to prevent duplicate events.

The evaluator synchronizes on the `ScalingRegistration` object during evaluation,
matching `AdaptiveTopologyManager`'s `synchronized(state)` per-tenant pattern.
This serializes concurrent evaluations for the same registration while allowing
different registrations to evaluate in parallel.

#### Transactional context

`@ObservesAsync` CDI observers run on worker pool threads without automatic
transactional context. The evaluator delegates the JPA query to an injected
`@Transactional` helper method on a separate CDI bean. This follows the existing
codebase pattern where no `@ObservesAsync` handler performs direct JPA access:
`DriftSignalBridge.onCloudEvent()` reads from in-memory `ConcurrentHashMap`,
`AdaptiveTopologyManager.onSituationChange()` reads from in-memory state.

```java
@ApplicationScoped
public class ScalingEvaluatorSupport {
    @Transactional
    public String loadServicesJson(UUID applicationId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        return app != null ? app.servicesJson : null;
    }
}
```

The evaluator injects `ScalingEvaluatorSupport` and calls `loadServicesJson()`
instead of accessing `ApplicationEntity` directly.

#### Registration record

```java
record ScalingRegistration(
    UUID appCaseId,
    String applicationId,
    Map<String, Integer> baseReplicas  // serviceId → replica count at registration
) {}
```

Keyed by `tenancyId + ":" + applicationId` in a `ConcurrentHashMap`.

#### Cooldown state

```java
ConcurrentHashMap<String, Instant> lastScalingTimestamps  // "appId:serviceId" → timestamp
```

Keyed per-service (not per-rule) because the service is the physical resource
being changed — there is only one replica count regardless of which rule
triggered the change. The effective cooldown period is `max(cooldownPeriod)` across
all rules for the service, protecting the service from rapid scaling regardless
of which rule triggers.

Set when a `ScalingRequestedEvent` is fired, not when scaling completes. This
matches the `TenantAdaptationState` pattern in the deployment module where
`lastChangePerRule` is set at decision time.

Does not own signaling — only evaluates rules and produces events.

### ScalingSignalBridge

`@ApplicationScoped` in `io.casehub.ops.app.service`.

Listens for `ScalingRequestedEvent` via `@ObservesAsync`. On each event:

1. Builds the scaling spec map (serviceId, targetReplicas, currentReplicas,
   applicationId, tenancyId, reason, plus policy fields)
2. Calls `CaseSignaler.signal(appCaseId, "scalingRequired", scalingSpec)`

Uses the extracted `CaseSignaler` interface (see §Existing Code Changes).

### ScalingResource

REST endpoint: `POST /api/applications/{id}/services/{serviceId}/scale`

Request body:

```json
{
  "targetReplicas": 5,
  "reason": "manual operator scale-up"
}
```

Handler:

1. Loads `ApplicationEntity`, validates status is RUNNING or DEGRADED
2. Resolves `appCaseId` from `app.engineCaseId`. Rejects with `409 Conflict` if
   `engineCaseId` is null (application has no active case — possible for DRAFT
   applications that passed status validation due to a race)
3. Parses services, finds the service, reads current replicas
4. Checks cooldown via `evaluator.isCoolingDown(applicationId, serviceId)`.
   Rejects with `429 Too Many Requests` if cooling down
5. Builds `ScalingPolicy` from the service's scaling rules (or `UNBOUNDED` if none)
6. Fires `ScalingRequestedEvent` via CDI async
7. Notifies the evaluator of the scaling timestamp via
   `evaluator.recordScalingTimestamp(applicationId, serviceId)`
8. Returns `202 Accepted`

**Manual scaling is temporary when scaling rules are active.** The next situation
evaluation cycle (event-driven or periodic re-poll) recomputes the target from
active situations and base replicas. If no situations are active, the service
reverts to its base replica count, overriding the manual change. This is
consistent with standard autoscaler semantics — K8s HPA, AWS ASG, and the
platform's own `AdaptiveTopologyManager` all override manual changes on the next
evaluation cycle. To permanently change the replica count for a service with
active scaling rules, update the `ServiceDefinition` through the normal deployment
pipeline (redeploy with the new `replicas` value, which becomes the new base).

When the service has scaling rules, the `202 Accepted` response includes a
`X-Scaling-Warning: active-rules` header indicating that the manual scaling will
be overridden by automatic evaluation. The response body includes:

```json
{
  "status": "accepted",
  "warning": "This service has automatic scaling rules — manual scaling will be overridden when situations change."
}
```

The REST path bypasses rule evaluation — the operator specifies the target directly.
`ScalingPolicy` clamp still applies because the child case's `evaluateScaling`
worker enforces it.

## Data Flow

```
RAS path:
  SituationChangeEvent
    → SituationScalingEvaluator
        → SituationSource.activeSituations(tenancyId)  [confidence query]
        → ScalingEvaluatorSupport.loadServicesJson()    [current servicesJson, @Transactional]
        → evaluate rules (max-wins if multiple match), check cooldown
    → ScalingRequestedEvent
    → ScalingSignalBridge (signal blackboard)
    → .scalingRequired on case blackboard
    → scaling-event child case spawns

Periodic re-poll (every 5 minutes):
  ScheduledExecutorService
    → for each registered tenant:
        → same evaluation logic as RAS path
        → ScalingRequestedEvent (only if target differs from current)

REST path:
  POST /scale
    → ScalingResource (validate, check cooldown via evaluator, build event)
    → ScalingRequestedEvent
    → ScalingSignalBridge (signal blackboard)
    → .scalingRequired on case blackboard
    → scaling-event child case spawns
```

## Registration Lifecycle

| Lifecycle event | Action |
|---|---|
| `deploy()` | `evaluator.register(tenancyId, appCaseId, applicationId, baseReplicasByService)` |
| `deploy()` | `driftSignalBridge.registerApplication(compositeKey, appCaseId, applicationId, clusterId)` |
| `decommission()` | `evaluator.deregister(tenancyId, applicationId)` |
| `decommission()` | `driftSignalBridge.deregisterApplication(compositeKey)` |
| `updateServiceReplicas()` | No action — evaluator reads `servicesJson` from DB on each evaluation |

The evaluator reads `servicesJson` from the database on each evaluation via
`ScalingEvaluatorSupport.loadServicesJson()` (a `@Transactional` helper). This
avoids stale-reference problems inherent in supplier-captured entity instances (JPA
entities become detached after transaction boundaries; subsequent
`updateServiceReplicas()` calls load fresh instances).

`DriftSignalBridge.registerApplication()` is currently unwired in production (only
called from tests). This spec wires both drift and scaling registration in `deploy()`
and `decommission()` for consistency.

## Architectural Boundary

The platform has two situation-reactive scaling systems operating at different
abstraction levels:

| Aspect | AdaptiveTopologyManager (deployment) | SituationScalingEvaluator (app) |
|--------|--------------------------------------|----------------------------------|
| Module | `casehub-ops-deployment` | `casehub-ops-app` |
| What it scales | Desiredstate graph nodes (agent instances) | K8s deployment replicas (pod count) |
| Mechanism | Recompiles graph from base goals + active situations | Updates `servicesJson` replica count, pushes new desired graph |
| Rule source | `adaptations:` in deployment YAML (`AdaptationRuleSpec`) | `scalingRules` in `ServiceDefinition` JSON |
| Revert | Automatic — base graph is recompiled without the adaptation | Explicit — evaluator targets base replicas when no situations match |
| Safety | Hysteresis (`deactivateBelow`), cooldown, periodic re-poll | Cooldown (max across rules), periodic re-poll (no hysteresis — see below) |

**No conflict risk**: the two systems cannot modify the same resource. Graph-level
adaptations add/remove graph nodes (agent instances via `ScaleAction` creating
`target~2`, `target~3` derived nodes). Service replica scaling changes the `replicas`
field on `ServiceDefinition`, which compiles to K8s Deployment `spec.replicas`. A
graph node and a K8s deployment replica are different resources managed through
different APIs.

**No hysteresis**: this spec omits hysteresis (`deactivateBelow`) intentionally.
Graph-level adaptations need hysteresis because graph mutations are expensive
(node creation, dependency resolution). K8s replica scaling is lightweight — changing
a deployment replica count is fast and idempotent. Cooldown alone provides sufficient
churn protection. Hysteresis can be added to `ScalingRule` later if needed.

## Existing Code Changes

1. **`ServiceDefinition`** — add `List<ScalingRule> scalingRules` field (11th field).
   `ServiceDefinitionParser` and test fixtures updated. Default: `List.of()`.

2. **`ApplicationCaseDescriptor`** — change input mapping from `.scalingSpec` to
   `.scalingRequired` (unification).

3. **`ScalingEventCaseDescriptor.evaluateScaling()`** — update error message from
   ".scalingSpec missing" to ".scalingRequired missing". Remove cooldown check
   (now enforced at evaluator/resource level). No other logic change.

4. **`ApplicationLifecycleService.deploy()`** — add `evaluator.register()` and
   `driftSignalBridge.registerApplication()` calls.

5. **`ApplicationLifecycleService.decommission()`** — add `evaluator.deregister()`
   and `driftSignalBridge.deregisterApplication()` calls.

6. **`ApplicationLifecycleService.updateServiceReplicas()`** — add `sd.scalingRules()`
   as 11th argument to `ServiceDefinition` constructor call (line 136).

7. **`DriftSignalBridge.CaseSignaler`** — extract to top-level interface
   `io.casehub.ops.app.service.CaseSignaler`. Both `DriftSignalBridge` and
   `ScalingSignalBridge` use the shared interface.

## ScalingPolicy Sourcing

The child case's `evaluateScaling` worker enforces policy clamping independently.
The evaluator computes the target from the rule; the child case clamps from policy
fields in the spec map.

When multiple rules match for the same service, the `ScalingPolicy` in the event
uses **merged bounds** from all matching rules:
- `minReplicas = min(all matching rules' minReplicas)`
- `maxReplicas = max(all matching rules' maxReplicas)`
- `cooldownPeriod = max(all matching rules' cooldownPeriods)`

This guarantees the max-wins target passes through the child case's clamp. The
max-wins target is at most the winning rule's `maxReplicas`, which is at most the
merged `maxReplicas` — so the clamp is always a no-op for the RAS path. For the
REST path, the merged policy provides the widest declared bounds across all active
rules, correctly bounding operator-specified targets.

When a single rule matches, its bounds are used directly (merged policy degenerates
to a single rule's policy).

Cooldown is enforced at the evaluator level (RAS path) and at the `ScalingResource`
level (REST path) before any event is fired. The child case does not check cooldown —
it is ephemeral and has no memory of previous scaling events.

## Testing Strategy

### ScalingRuleTest

- Confidence at `minConfidence` → `minReplicas`
- Confidence at 1.0 → `maxReplicas`
- Confidence midway → proportional intermediate
- Confidence below `minConfidence` → `minReplicas` (clamped)
- Confidence above 1.0 → `maxReplicas` (clamped)
- `minReplicas == maxReplicas` → always that value

### SituationScalingEvaluatorTest

- Situation matches rule → fires `ScalingRequestedEvent` with correct target
- Situation below `minConfidence` → no event
- No registered apps for tenant → no event
- Service with no scaling rules → skipped
- Multiple services with rules for same situation → separate events per service
- Target equals current replicas → no event (no-op suppressed)
- Multiple rules on one service for different situations → only matching rule fires
- Multiple rules match simultaneously → max target wins
- Multiple rules match → merged policy uses min(minReplicas), max(maxReplicas)
- Concurrent CDI event and periodic re-poll for same service → serialized, single event
- Registration/deregistration lifecycle
- `ChangeType.RESOLVED` → evaluator recomputes; if no active situations match,
  fires scale-down event targeting base replicas
- `ChangeType.DISCARDED` → evaluator recomputes; same as RESOLVED
- Situation previously active but no longer in `activeSituations()` list → scale-down
- Cooldown suppression: event within cooldown period → no event fired
- Cooldown uses max period across all rules for the service
- Cooldown expiry: event after cooldown period → event fires normally
- Evaluator queries `SituationSource.activeSituations()` for confidence values
- Periodic re-poll detects missed situation change → fires event
- Periodic re-poll during cooldown → no event (cooldown deduplicates)
- Manual REST scaling is overridden by next situation evaluation

### ScalingSignalBridgeTest

- Event received → signals `scalingRequired` on correct case with full spec map
- Spec map contains all expected fields

### ScalingResourceTest

- Valid POST → `202 Accepted`, event fired
- Application not found → `404`
- Wrong application status → `409 Conflict`
- Null `engineCaseId` → `409 Conflict`
- Service not found → `404`
- Missing `targetReplicas` → `400`
- Request during cooldown → `429 Too Many Requests`
- Service with scaling rules → response includes `X-Scaling-Warning` header
  and warning in body

### Existing test updates

- `ApplicationCaseDescriptorTest` — verify unified `.scalingRequired` binding
- `ServiceDefinition` parsing — round-trip with `scalingRules`
- `ScalingEventCaseDescriptor` tests — remove cooldown assertion (cooldown no
  longer checked at child case level)
