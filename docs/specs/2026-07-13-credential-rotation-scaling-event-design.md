# Credential Rotation & Scaling-Event Child Case

**Date:** 2026-07-13
**Issues:** #51 (credential rotation), #35 (scaling-event child case — partial: workers only, trigger mechanism deferred)
**Branch:** issue-51-credential-rotation-scaling-child
**Module:** app/

---

## 1. Credential Rotation in K8sClientRegistry (#51)

### Problem

`K8sClientRegistry` caches `KubernetesClient` instances with no TTL. When credentials
have an `expires-at`, the cached client continues using expired credentials until restart.
`K8sWatchManager` holds open Watch connections on the stale client — watches silently fail
on credential expiry with no reconnection.

### Design

#### 1.1 ClientEntry — enriched cache entry

Replace the flat `ConcurrentHashMap<String, KubernetesClient>` with:

```java
record ClientEntry(KubernetesClient client, String apiUrl, String credentialRef,
                   boolean trustCerts, Instant expiresAt) {}
```

`register()` resolves credentials via `CredentialResolver`, parses `expires-at` from the
result map (ISO-8601 instant), and stores it in the entry. Entries without `expires-at`
have `expiresAt = null` — they are never proactively refreshed.

**Re-registration:** The current `putIfAbsent` behavior silently discards re-registration
parameters, freezing metadata at first registration. With `ClientEntry` storing
`credentialRef` for ongoing proactive refresh, stale metadata means refreshing from the
wrong credential source. `register()` uses `compute()` instead: if an entry already
exists, it updates the metadata (`credentialRef`, `apiUrl`, `trustCerts`, `expiresAt`)
without replacing the client, preserving existing connections and watches. The proactive
scan picks up the updated `credentialRef` on the next cycle and refreshes with the
correct source. If no entry exists, a new client is built and stored as before.

#### 1.2 refreshClient — atomic client replacement

```java
public void refreshClient(String clusterId)
```

- Re-resolves credentials via `CredentialResolver.resolve(credentialRef)` using the stored
  `credentialRef` from the current entry
- Builds a new `KubernetesClient` with the fresh credentials
- Atomically replaces the entry in the map (retaining old client reference)
- Fires `CredentialRefreshedEvent` via CDI (`Event<CredentialRefreshedEvent>`) — synchronous
  delivery, so the `K8sWatchManager` handler completes (stops watches on stale connections,
  restarts on new client) before this method proceeds
- Closes the old client — at this point no watches hold connections on it, so no spurious
  "Watch disconnected" warnings

**Coalescing:** Concurrent `refreshClient()` calls for the same clusterId must not
stampede. A `ConcurrentHashMap<String, CompletableFuture<Void>>` tracks in-flight
refreshes. If a refresh is already in progress for a clusterId, subsequent calls join
the existing future rather than starting a new one. The future is removed from the map
in a `whenComplete` callback (on both success and failure) so that subsequent calls
after completion trigger a fresh refresh rather than joining a stale completed future.

#### 1.3 Proactive refresh — @Scheduled scan

A `@Scheduled(every = "60s")` method scans all entries. For each entry with a non-null
`expiresAt`:

- Compute remaining TTL: `Duration.between(Instant.now(), expiresAt)`
- If remaining TTL < 5 minutes: call `refreshClient()`

**5 minutes** is the threshold — sufficient for any reasonable credential rotation period
while avoiding unnecessary refresh churn.

The scan loop catches exceptions per-entry and logs a warning, continuing to the next
entry. This handles the race where `deregister()` removes a cluster between the scan
snapshot and the `refreshClient()` call — the entry is gone, the call fails, but
remaining entries are still refreshed.

#### 1.4 CredentialRefreshedEvent — CDI notification

```java
public record CredentialRefreshedEvent(String clusterId) {}
```

Fired by `refreshClient()` after successful client replacement. Observed by
`K8sWatchManager` to restart watches.

#### 1.5 K8sWatchManager — watch restart on credential refresh

Add `@Observes CredentialRefreshedEvent` handler (synchronous — must complete before
`refreshClient()` closes the old client):

```
onCredentialRefreshed(event):
  Set<String> namespaces = stopWatching(event.clusterId())
  for each namespace in namespaces:
    startWatching(event.clusterId(), namespace)
```

`stopWatching(String clusterId)` is modified to return `Set<String>` — the namespaces
extracted from removed `activeWatches` keys (key format `clusterId:namespace`). This
avoids a race between a separate namespace query and the stop call, and requires no
additional tracking state.

### Files Changed

| File | Change |
|------|--------|
| `K8sClientRegistry` | Replace `ConcurrentHashMap<String, KubernetesClient>` with `ConcurrentHashMap<String, ClientEntry>`. Add `refreshClient()` with coalescing. Add `@Scheduled` proactive scan. Store `credentialRef`, `apiUrl`, `trustCerts`, `expiresAt` per entry. |
| `CredentialRefreshedEvent` (new) | CDI event record: `clusterId` |
| `K8sWatchManager` | Add `@Observes CredentialRefreshedEvent` handler (synchronous). `stopWatching()` returns `Set<String>` of removed namespaces. |

### Tests

| Test | Covers |
|------|--------|
| `K8sClientRegistryTest.refreshReplacesClient` | refreshClient builds new client, old is closed |
| `K8sClientRegistryTest.refreshPreservesRegistration` | after refresh, clientFor() returns working client |
| `K8sClientRegistryTest.refreshCoalescesConcurrentCalls` | two concurrent refreshClient() calls resolve once |
| `K8sClientRegistryTest.proactiveScanRefreshesApproachingExpiry` | entry with expiresAt < 5min triggers refresh |
| `K8sClientRegistryTest.proactiveScanSkipsNullExpiry` | entry without expiresAt is not refreshed |
| `K8sClientRegistryTest.proactiveScanSkipsDistantExpiry` | entry with expiresAt > 5min is not refreshed |
| `K8sClientRegistryTest.registerParsesExpiresAt` | register with expires-at credential stores Instant |
| `K8sClientRegistryTest.registerWithoutExpiresAtStoresNull` | register without expires-at stores null expiresAt |
| `K8sClientRegistryTest.reRegisterUpdatesMetadataWithoutReplacingClient` | re-registration updates credentialRef/expiresAt, keeps existing client |
| `K8sClientRegistryTest.refreshUnknownClusterThrows` | refreshClient for non-existent cluster throws |
| `K8sWatchManagerTest.credentialRefreshRestartsWatches` | CredentialRefreshedEvent stops and restarts watches |
| `K8sWatchManagerTest.credentialRefreshForUnwatchedClusterIsNoOp` | event for non-watched cluster does nothing |

---

## 2. Scaling-Event Child Case (#35)

### Problem

The scaling-event child case is a stub — spawned when `.scalingRequired` is written to the
parent application lifecycle case's blackboard, but does nothing. Needs real implementation:
evaluate the scaling request, update the persistent desired state (replicas in
`ApplicationEntity.servicesJson`), trigger recompilation and reconciliation, and verify
convergence.

### Design

#### 2.1 ScalingEventCaseDescriptor

Replaces `StubChildCaseDescriptor.build("ops", "scaling-event", "1.0")` in
`CaseDefinitionRegistrar`. Follows the `DriftRemediationCaseDescriptor` pattern:

**Capabilities:** `evaluate-scaling`, `execute-scaling`, `verify-convergence`

**Input contract:** The parent `ApplicationCaseDescriptor` binding triggers on
`.scalingRequired` (any truthy value) and maps `.scalingSpec` as child case input.
These are two separate parent blackboard entries:
- `.scalingRequired` — trigger field, written by the external signal source (e.g. RAS
  adapter, REST API, or monitoring integration — trigger mechanism is out of scope, see §3)
- `.scalingSpec` — structured scaling specification, mapped as child case worker input

If `.scalingSpec` is missing or malformed when `.scalingRequired` triggers, the
evaluate worker receives null/invalid input and returns `WorkerResult.failed()` with
a descriptive reason. The child case terminates without side effects.

**Workers:**

1. **evaluate-scaling-worker** — receives `.scalingSpec` value as input:
   ```json
   {
     "applicationId": "uuid",
     "tenancyId": "tenant-1",
     "serviceId": "order-processor",
     "currentReplicas": 3,
     "targetReplicas": 6,
     "reason": "cpu_threshold_exceeded",
     "metrics": { "cpuPercent": 87.5 }
   }
   ```
   `applicationId` and `tenancyId` are required for the execute worker to call
   `updateServiceReplicas()`. The signal source populates these from the parent case
   context when writing `.scalingSpec` to the parent blackboard.

   Validates:
   - `targetReplicas > 0`
   - `targetReplicas != currentReplicas` (no-op → writes `scalingStatus = "no-change-needed"`, case closes)
   - `serviceId` is non-null and non-blank

   `currentReplicas` defaults to 0 when absent or null — this allows scaling requests
   from sources that don't track current replica counts (e.g. initial provisioning).
   The value is informational for the evaluate worker (determines scale direction and
   no-op detection) and is forwarded to the execute worker's audit trail via
   `scalingDecision.currentReplicas`.

   On valid request: outputs `scalingDecision` with `action` ("scale-up" or "scale-down"),
   `applicationId`, `tenancyId`, `serviceId`, `validatedTarget`, `currentReplicas`.

   On invalid request: returns `WorkerResult.failed()` with reason.

2. **execute-scaling-worker** — triggered by binding on `.scalingDecision`.
   Calls `ApplicationLifecycleService.updateServiceReplicas(appId, serviceId, newReplicas, tenancyId)`.
   The `ApplicationLifecycleService` is passed into `ScalingEventCaseDescriptor.build()` and
   captured in the worker lambda closure. `CaseDefinitionRegistrar` adds an `@Inject
   ApplicationLifecycleService` field for this purpose (see §2.3 for the pattern implications).

   - Patches `ApplicationEntity.servicesJson` — updates `replicas` for the target serviceId
   - Recompiles the goal via `ApplicationGoalCompiler`
   - Pushes updated graph to `ReconciliationLoop.updateDesired()`

   Outputs `scalingExecuted` with `serviceId`, `previousReplicas`, `newReplicas`,
   and `affectedNodeIds` (the set of deployment node IDs affected by the scaling
   operation, returned by `updateServiceReplicas()` — see §2.2). The verify worker
   needs these node IDs to register convergence tracking.

3. **verify-convergence-worker** — triggered by binding on `.scalingExecuted`.
   Reads `affectedNodeIds` from the `scalingExecuted` blackboard entry (written by the
   execute worker — see above). Registers the scaling case with `NodeConvergenceTracker`,
   providing the child case ID, the affected node IDs, signal path `"scalingStatus"`, and
   signal value `"converged"`. The worker completes without
   writing `scalingStatus` — convergence is signaled asynchronously by the tracker.

**NodeConvergenceTracker (new service)** — replaces `DriftConvergenceHandler` with a
generic, parameterized convergence tracker. Single `@ApplicationScoped` CDI bean with
one `@ObservesAsync CloudEvent` handler (eliminating the doubled CloudEvent fan-out that
a separate `ScalingConvergenceHandler` would introduce).

```java
record CaseTracking(Set<String> pendingNodeIds,
                    String signalPath, Object signalValue) {}

ConcurrentHashMap<UUID, CaseTracking> tracked;

void register(UUID caseId, Set<String> nodeIds,
              String signalPath, Object signalValue)
```

Observes `NODE_RECOVERED` CloudEvents from the reconciliation loop. Tracks pending node
IDs per case. When all nodes for a case reach the desired state, signals convergence to
the case blackboard via `CaseHubRuntime.signal()` using the registered signal path and
value. If nodes fail to converge (e.g. pods stuck in Pending due to insufficient
resources, image pull failures, or resource quota violations), the tracker does not
signal — the case remains open, providing visibility into the failure.

**Drift convergence wiring (new):** `DriftConvergenceHandler.registerDriftCase()` has
zero production callers — the handler is unwired infrastructure (CDI-instantiated,
observing every CloudEvent, iterating an always-empty map). The drift remediation
completion predicate (`.remediationStatus == "converged"`) is currently unreachable:
the remediate worker writes `"auto-remediating"` but only the convergence handler can
write `"converged"`, and nothing registers drift cases with it.

This spec adds the missing wiring. The `drift-remediate-worker` on the benign
(auto-remediate) path now:

1. Extracts `nodeIds` from the `driftClassification` input (populated by the classify
   worker from `driftDetails`)
2. Gets the child case ID via `WorkerExecutionContext.current().caseId()`
3. Calls `tracker.register(caseId, nodeIds, "remediationStatus", "converged")`
   to register convergence tracking
4. Returns `WorkerResult.of(Map.of("remediationStatus", "auto-remediating"))` as before
   — the intermediate status provides observability while convergence is pending

On the critical (escalation) path, the worker does NOT register convergence tracking —
escalated cases complete via a different mechanism.

The worker changes from a static method reference (`DriftRemediationCaseDescriptor::remediateDrift`)
to a lambda capturing the `NodeConvergenceTracker` instance. `DriftRemediationCaseDescriptor.build(tracker)`
passes the tracker for this purpose.

`DriftConvergenceHandler` is deleted — `NodeConvergenceTracker` subsumes its role with
correct wiring.

**Internal bindings:**
- `on-scaling-decision` — triggers on `.scalingDecision`, dispatches `execute-scaling`
- `on-scaling-executed` — triggers on `.scalingExecuted`, dispatches `verify-convergence`

**Completion predicate:** `.scalingStatus == "converged"` OR `.scalingStatus == "no-change-needed"`

#### 2.2 ApplicationLifecycleService.updateServiceReplicas

New method:

```java
public Set<String> updateServiceReplicas(UUID applicationId, String serviceId,
                                          int newReplicas, String tenancyId)
```

Returns the set of affected deployment node IDs (format: `clusterId + ":" + serviceId +
":deployment"` per cluster — deterministic from the goal compiler). The execute worker
passes these to the verify worker via the blackboard so convergence tracking can be
registered without the verify worker needing access to `ClusterService`.

- Reads `ApplicationEntity`, checks `status` — rejects with `IllegalStateException` unless
  status is `RUNNING` or `DEGRADED` (reconciliation loops must be active; `DEPLOYING` is
  rejected to prevent race with concurrent `deploy()` calls)
- Parses `servicesJson`, finds the `ServiceDefinition` matching `serviceId`, replaces `replicas`
- Serializes back to JSON, updates `ApplicationEntity.servicesJson`
- Recompiles graphs for all clusters via `compileForCluster()` loop (same pattern as `deploy()`)
- Collects deployment node IDs from each cluster iteration: `clusterId + ":" + serviceId + ":deployment"`
- Pushes each updated graph to `ReconciliationLoop.updateDesired()`
- Returns the collected deployment node IDs

**Concurrent deploy/scale race:** The DEPLOYING status guard prevents scaling during
deploy. The reverse — `deploy()` called while `updateServiceReplicas()` is in progress —
is unguarded. This requires simultaneous REST API calls on the same application
(operational error). If it occurs, the last `updateDesired()` call wins. The
`ScalingConvergenceHandler` detects the discrepancy: deployment nodes won't converge to
the target replicas, so the scaling case stays open rather than silently claiming success.
A subsequent scaling event corrects the state. Full mutual exclusion (application-level
pessimistic lock or a `SCALING` status) is deferred — the race requires operator error
and is self-revealing via the convergence handler.

#### 2.3 CaseDefinitionRegistrar update

Replace:
```java
StubChildCaseDescriptor.build("ops", "scaling-event", "1.0"),
```
With:
```java
ScalingEventCaseDescriptor.build(applicationLifecycleService, convergenceTracker),
DriftRemediationCaseDescriptor.build(convergenceTracker),
```

`CaseDefinitionRegistrar` adds `@Inject ApplicationLifecycleService` and
`@Inject NodeConvergenceTracker` fields. This changes the existing pattern — descriptors
were previously pure static `build()` methods with no CDI dependencies.
`DriftRemediationCaseDescriptor.build(tracker)` and
`ScalingEventCaseDescriptor.build(service, tracker)` capture CDI service references in
worker lambda closures. The pattern is sound — workers with genuine side effects
(updating persistent state, registering convergence tracking) require these service
references — but changes the contract: descriptors are no longer pure value builders.

### Files Changed

| File | Change |
|------|--------|
| `ScalingEventCaseDescriptor` (new) | 3 capabilities, 3 workers, 2 internal bindings, completion predicate. `build(ApplicationLifecycleService, NodeConvergenceTracker)` captures both in worker lambda closures. |
| `NodeConvergenceTracker` (new) | Generic convergence tracker. Parameterized registration with signal path/value. Single `@ObservesAsync CloudEvent` handler for all convergence tracking. |
| `DriftConvergenceHandler` (deleted) | Replaced by `NodeConvergenceTracker`. |
| `DriftRemediationCaseDescriptor` | Add `build(NodeConvergenceTracker)` parameter. Remediate worker changes from static method reference to lambda capturing tracker. On benign path: registers convergence tracking via `NodeConvergenceTracker.register()` with `"remediationStatus"` signal path (new wiring — previously unwired). |
| `CaseDefinitionRegistrar` | Inject `ApplicationLifecycleService` and `NodeConvergenceTracker`. Replace scaling-event stub with `ScalingEventCaseDescriptor.build(lifecycleService, convergenceTracker)`. Pass `convergenceTracker` to `DriftRemediationCaseDescriptor.build()`. |
| `ApplicationLifecycleService` | Add `updateServiceReplicas()` returning `Set<String>` of affected deployment node IDs, with status guard (RUNNING/DEGRADED only) |
| `K8sWatchManager` | `stopWatching()` returns `Set<String>` of removed namespaces |

### Tests

| Test | Covers |
|------|--------|
| `ScalingEventCaseDescriptorTest.buildReturnsCorrectIdentity` | namespace, name, version |
| `ScalingEventCaseDescriptorTest.hasThreeCapabilities` | evaluate, execute, verify |
| `ScalingEventCaseDescriptorTest.hasThreeWorkers` | worker count |
| `ScalingEventCaseDescriptorTest.hasTwoInternalBindings` | decision and executed bindings |
| `ScalingEventCaseDescriptorTest.hasCompletionPredicate` | completion expression present |
| `ScalingEventCaseDescriptorTest.evaluateValidScaleUp` | valid scale-up produces correct decision |
| `ScalingEventCaseDescriptorTest.evaluateValidScaleDown` | valid scale-down produces correct decision |
| `ScalingEventCaseDescriptorTest.evaluateNoOpSameReplicas` | same replicas → no-change-needed |
| `ScalingEventCaseDescriptorTest.evaluateRejectsZeroTarget` | targetReplicas=0 → failed |
| `ScalingEventCaseDescriptorTest.evaluateRejectsNegativeTarget` | targetReplicas < 0 → failed |
| `ScalingEventCaseDescriptorTest.evaluateRejectsBlankServiceId` | blank serviceId → failed |
| `ScalingEventCaseDescriptorTest.executeOutputsAuditTrailWithNodeIds` | execute returns old/new counts and affectedNodeIds |
| `ScalingEventCaseDescriptorTest.verifyRegistersWithConvergenceTracker` | verify registers case with NodeConvergenceTracker using scalingStatus signal |
| `NodeConvergenceTrackerTest.signalsConvergedWhenAllNodesRecovered` | all nodes recovered → signals with registered path/value |
| `NodeConvergenceTrackerTest.doesNotSignalUntilAllNodesRecovered` | partial recovery → no signal |
| `NodeConvergenceTrackerTest.ignoresEventsForUnregisteredCases` | events for unknown cases are no-ops |
| `NodeConvergenceTrackerTest.tracksMultipleCasesWithDifferentSignalPaths` | drift (remediationStatus) and scaling (scalingStatus) cases tracked independently |
| `DriftRemediationCaseDescriptorTest.remediateBenignRegistersConvergence` | benign path registers with NodeConvergenceTracker using remediationStatus signal |
| `DriftRemediationCaseDescriptorTest.remediateCriticalDoesNotRegisterConvergence` | critical/escalation path does not register convergence tracking |
| `ApplicationLifecycleServiceTest.updateServiceReplicasRejectsDraftStatus` | DRAFT status → IllegalStateException |
| `ApplicationLifecycleServiceTest.updateServiceReplicasRejectsDeployingStatus` | DEPLOYING status → IllegalStateException |
| `ApplicationLifecycleServiceTest.updateServiceReplicasPatchesJson` | replicas updated in servicesJson |
| `ApplicationLifecycleServiceTest.updateServiceReplicasReturnsAffectedNodeIds` | returns deployment node IDs for all clusters |
| `ApplicationLifecycleServiceTest.updateServiceReplicasUnknownServiceThrows` | unknown serviceId → exception |

---

## 3. Deferred / Out of Scope

| Item | Reason | Action |
|------|--------|--------|
| Scaling bounds (min/max replicas per service) | Needs `ScalingPolicy` model — separate design | File issue |
| Cooldown period between scaling events | Needs timestamp tracking in parent case | File issue |
| Reactive 401 catch in K8s callers | Registry provides `refreshClient()`; wiring callers is follow-up | File issue |
| Scaling trigger mechanism | What writes `.scalingRequired`/`.scalingSpec` to parent blackboard (RAS, REST, monitoring) | File issue |

---

## 4. Integration Points

#51 and #35 are independent features:
- Credential rotation is infrastructure plumbing in `app/k8s/`
- Scaling-event is domain logic in `app/case_/` and `app/service/`
- No interaction between them
