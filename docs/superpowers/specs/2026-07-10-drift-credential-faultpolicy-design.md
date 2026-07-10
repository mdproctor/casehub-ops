# Drift Remediation, Credential Resolver, FaultPolicy Migration — Design Spec

Issues: #36, #44, #42
Branch: `issue-36-drift-credential-faultpolicy`

## Summary

Three issues on one branch:

1. **#42 (FaultPolicy 3-arg migration)** — already complete. All four domain FaultPolicy
   implementations and tests already use the 3-arg `onFault(FaultEvent, DesiredStateGraph, ActualState)`
   signature. Close immediately.

2. **#44 (Pluggable credential resolver)** — wire the platform's existing `CredentialResolver` SPI
   into `K8sClientRegistry` so that `ClusterReferenceEntity.credentialRef` is resolved and applied
   to fabric8 client creation.

3. **#36 (Drift-remediation child case)** — build the case model infrastructure
   (`ApplicationCaseDescriptor`, bindings, child case types) as Phase 3 foundation, then implement
   the `ops:drift-remediation` child case with real workers. Other child cases are wired but stubbed.

## Issue #42 — FaultPolicy 3-arg Migration

### Status: Complete

All four domain module implementations already have the correct signature:

| Class | Module | Signature |
|-------|--------|-----------|
| `ComplianceFaultPolicy` | compliance | `onFault(FaultEvent, DesiredStateGraph, ActualState)` ✅ |
| `DeploymentFaultPolicy` | deployment | `onFault(FaultEvent, DesiredStateGraph, ActualState)` ✅ |
| `InfraFaultPolicy` | infra | `onFault(FaultEvent, DesiredStateGraph, ActualState)` ✅ |
| `IoTFaultPolicy` | iot | `onFault(FaultEvent, DesiredStateGraph, ActualState)` ✅ |

All tests pass the 3-arg `ActualState` parameter. Fixed by commits on #50 and prior work.

### Action

Close #42 with comment referencing the commits that completed the migration.

## Issue #44 — Pluggable Credential Resolver

### Current State

`K8sClientRegistry.register(clusterId, apiUrl)` creates fabric8 clients with just the URL and
`trustCerts(true)`. `ClusterReferenceEntity` has a `credentialRef` field that is never used.
`RegisterClusterRequest` already includes `credentialRef` — no DTO changes needed.

The platform already provides the SPI:

- `CredentialResolver` interface in `casehub-platform-api`: `Map<String, String> resolve(String credentialRef)`
- `DefaultCredentialResolver` in `casehub-platform`: `@DefaultBean @ApplicationScoped`, reads from
  MicroProfile Config (`casehub.credentials.<ref>.*`). Supports compound keys: `user`, `password`,
  `bearer-token`, `api-key`, `expires-at`. Falls back to single-value bearer token.

### Design

**K8sClientRegistry changes:**

1. Inject `CredentialResolver` via constructor injection
2. Change `register(String clusterId, String apiUrl)` → `register(String clusterId, String apiUrl, String credentialRef)`
3. When `credentialRef` is non-null and non-blank:
   - Call `credentialResolver.resolve(credentialRef)` → `Map<String, String>`
   - If map is empty and `credentialRef` was explicitly set: log a WARNING
     ("Credential reference '{}' resolved to empty map — possible misconfiguration. Falling back to auto-detection.")
     This distinguishes misconfigured refs from deliberately unconfigured clusters.
   - Apply non-empty credentials to fabric8 `ConfigBuilder`:
     - Key `bearer-token` → `config.withOauthToken(value)`
     - Keys `user` + `password` → `config.withUsername(user).withPassword(password)`
     - Key `api-key` → `config.withCustomHeaders(Map.of("Authorization", "ApiKey " + value))`
   - When map is empty (after warning): fall through to fabric8 auto-detection
4. Keep the old `register(String, String)` signature as a convenience that delegates with `null` credentialRef
5. Remove hardcoded `withTrustCerts(true)` — replace with per-cluster `trustCerts` parameter:
   `register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts)`.
   Default `true` for backward compatibility in the 2-arg overload; production clusters should
   pass `false`. `ClusterReferenceEntity` already has enough fields; add `trustCerts` boolean.

**Upstream wiring — actual call sites that need the 3-arg register():**

Two code paths create fabric8 clients (not `ClusterService` — it only persists the entity):

1. `ApplicationLifecycleService.deploy()` — currently calls
   `clientRegistry.register(cluster.id.toString(), cluster.apiUrl)`. Change to pass
   `cluster.credentialRef` and `cluster.trustCerts`.
2. `StartupRecoveryService.recover()` — uses `BiConsumer<String, String> clusterRegistrar`
   functional interface. Needs to become a `ClusterRegistrar` functional interface
   (or similar) accepting `(String clusterId, String apiUrl, String credentialRef, boolean trustCerts)`.
   Both the CDI-injected constructor and the test constructor need updating.

**Credential expiration:** `DefaultCredentialResolver` supports an `expires-at` key, and
`K8sClientRegistry` caches clients with no TTL. For this scope, credential rotation is out of
scope — file a tracking issue for lazy re-resolve on auth failure and periodic refresh.

### Testing

- `K8sClientRegistryTest`: test credential application (bearer token, user/password, empty map
  with warning, null ref, trustCerts=false)
- Verify `DefaultCredentialResolver` integration via MicroProfile Config in test profile

## Issue #36 — Drift-Remediation Child Case

### Prerequisites Built: Case Model Infrastructure

The spec (2026-07-05-ops-console-app-design.md §Case Model) defines the case infrastructure as
Phase 3. No case-related code exists yet. This branch builds the foundation.

### A. ApplicationCaseDescriptor

New package: `app/src/main/java/io/casehub/ops/app/case_/`

`ApplicationCaseDescriptor` builds a `CaseDefinition` for the parent application case:

- **Namespace:** `ops`
- **Name:** `application-lifecycle`
- **Version:** `1.0`
- **Bindings:** 6 context-change bindings, each spawning a child sub-case:

| Binding name | Trigger (JQ filter) | Child case type | Input mapping | Status |
|-------------|---------------------|-----------------|---------------|--------|
| `on-drift-detected` | `.driftDetected` | `ops:drift-remediation:1.0` | `.driftDetected` | **Working** |
| `on-cve-detected` | `.cveDetected` | `ops:cve-response:1.0` | `.cveData` | Stubbed |
| `on-upgrade-requested` | `.upgradeRequested` | `ops:service-upgrade:1.0` | `.upgradeSpec` | Stubbed |
| `on-incident-detected` | `.incidentDetected` | `ops:incident-response:1.0` | `.incidentData` | Stubbed |
| `on-scaling-required` | `.scalingRequired` | `ops:scaling-event:1.0` | `.scalingSpec` | Stubbed |
| `on-compliance-violation` | `.complianceViolation` | `ops:compliance-remediation:1.0` | `.violationData` | Stubbed |

Note: `ContextChangeTrigger` takes a JQ expression, not a JSON path. JQ field access requires a
leading `.` — e.g., `.driftDetected`, not `driftDetected` (bare identifier is an invalid JQ function call).

Each binding uses:
- `new ContextChangeTrigger(".fieldName")` as the trigger
- `SubCase.builder().namespace("ops").name(childType).version("1.0").inputMapping(mapping).waitForCompletion(false).build()` as the target

**Stubbed child case definitions:** For the 5 non-drift child cases, `ApplicationCaseDescriptor`
also builds their `CaseDefinition`s with a single no-op worker each. These are registered with
the engine so the bindings can fire and child cases can spawn — they just don't do meaningful work.

**FaultPolicy clarification:** The app module has two FaultPolicy classes:
- `KubernetesFaultPolicy` — production FaultPolicy for K8s operations (the spec's "ApplicationFaultPolicy")
- `StubFaultPolicy` — CDI fallback for domains not on the classpath (via `FaultPolicyListProducer`)

Neither is affected by this branch. They are part of the desiredstate SPI quad, not the case model.

### B. Case Definition Registration and Instance Creation

**Definition registration:** A new `@Startup` CDI bean, `CaseDefinitionRegistrar`, handles
registration:

1. On application startup (`@Observes @Priority(10) StartupEvent`):
   - Builds all 7 `CaseDefinition` objects via their descriptors
   - Registers each with `CaseDefinitionRegistry` (engine API)
2. Child case definitions are registered globally (once), not per-application — the
   definitions describe case types, not case instances
3. Child definitions must be registered before the parent case's bindings can fire —
   `@Priority(10)` ensures this runs before `StartupRecoveryService` (`@Priority(20)`)
   since CDI does not guarantee observer order for the same event type without `@Priority`
4. On restart recovery, `StartupRecoveryService` does NOT re-register definitions —
   `CaseDefinitionRegistrar` handles that via `@Startup`. Recovery only re-creates
   reconciliation loops for active applications.

**Case instance creation:** `ApplicationLifecycleService.deploy()` creates the parent case
instance:

1. Calls `CaseHubRuntime.startCase(applicationLifecycleDefinition, Map.of("topology", application))`
2. Stores the returned `UUID` (the `appCaseId`) in `ApplicationEntity.caseId` (new field,
   nullable — null means DRAFT, non-null means deployed/active)
3. The `appCaseId` is used for all subsequent `runtime.signal()` calls (drift, CVE, etc.)
4. On restart recovery: the engine persists case instances in its own store — `StartupRecoveryService`
   reads `ApplicationEntity.caseId` to obtain the existing case ID, does NOT re-create cases.
   It only re-starts reconciliation loops.

### C. DriftRemediationCaseDescriptor

Builds `CaseDefinition` for `ops:drift-remediation`:

- **Namespace:** `ops`
- **Name:** `drift-remediation`
- **Version:** `1.0`

**Capabilities:**

| Name | Purpose |
|------|---------|
| `classify-drift` | Examine drift report, determine severity |
| `remediate-drift` | Monitor reconciliation convergence |
| `escalate-drift` | Create human WorkItem for critical/persistent drift |

**Workers:**

1. **`drift-classify-worker`** (capability: `classify-drift`)
   - Receives `DriftReport` from case input data (via binding input mapping)
   - Classifies severity using `DriftReport.driftDetails` (per-node field-level diffs):
     - **Benign:** single-node drift, first occurrence (`consecutiveDriftCount == 1`),
       non-security fields only (replicas, labels, annotations)
     - **Critical:** persistent drift (`consecutiveDriftCount > 1`), security-sensitive fields
       (image, serviceAccount, RBAC rules, secrets), or multiple nodes drifted simultaneously
   - Returns `WorkerResult` that writes to case context:
     - `driftClassification.severity` = `"benign"` | `"critical"`
     - `driftClassification.reason` = human-readable explanation
     - `driftClassification.nodeIds` = list of affected node IDs

2. **`drift-remediate-worker`** (capability: `remediate-drift`)
   - Triggered by binding on `.driftClassification` context change
   - For benign: the reconciliation loop is already re-provisioning. Worker writes
     `remediationStatus = "auto-remediating"` and completes. Convergence detection (§F)
     handles the rest.
   - For critical: writes `escalationRequired = true` to case context and completes.

3. **`drift-escalate-worker`** (capability: `escalate-drift`)
   - Triggered by binding on `.escalationRequired` context change (when value is `true`)
   - Creates a human WorkItem via the casehub-work bridge:
     - Summary: "Persistent drift detected on {nodeCount} nodes in cluster {clusterId}"
     - Detail: which nodes, what changed (from `driftDetails`), how many consecutive cycles
     - Risk: HIGH (persistent drift indicates either external tampering or configuration conflict)
   - Completes after WorkItem creation. Human approval/rejection flows through the existing
     work-adapter mechanism.

**Bindings (within drift-remediation case):**

| Name | Trigger (JQ) | Target |
|------|-------------|--------|
| `on-classification-complete` | `.driftClassification` | capability `remediate-drift` |
| `on-escalation-required` | `.escalationRequired` | capability `escalate-drift` |

**Completion:**

Uses the `CaseDefinition.Builder.completion(String when)` API which takes a JQ predicate
evaluated against the case context. The expression:

```java
.completion(".remediationStatus == \"converged\"")
```

This creates a `PredicateBasedCompletion` with a `JQExpressionEvaluator`. The engine evaluates
this after each context write — when `remediationStatus` is set to `"converged"`, the case
completes with SUCCESS.

No automatic FAILURE — persistent drift keeps the case open for human visibility. The case is
cancelled externally if the application is decommissioned.

### D. DriftReport Model

New records in `app/src/main/java/io/casehub/ops/app/model/`:

```java
public record FieldDrift(
    String fieldName,
    String expectedValue,
    String actualValue
) {}

public record NodeDrift(
    String nodeId,
    List<FieldDrift> fields
) {}

public record DriftReport(
    List<NodeDrift> driftDetails,
    String clusterId,
    String applicationId,
    Instant detectedAt,
    int consecutiveDriftCount
) {
    public List<String> driftedNodeIds() {
        return driftDetails.stream().map(NodeDrift::nodeId).toList();
    }

    public boolean hasSecuritySensitiveFields() {
        return driftDetails.stream()
                .flatMap(nd -> nd.fields().stream())
                .anyMatch(f -> Set.of("image", "serviceAccount", "rbac", "secrets")
                        .contains(f.fieldName()));
    }
}
```

### E. Drift Signal Bridge — CloudEvent Observer Pattern

The bridge observes **reconciliation output CloudEvents**, following the established pattern
used by `DeploymentOutcomeTracker` and `DecommissionCompletionHandler`.

**Established pattern:** `ReconciliationLoop`'s CDI constructor takes `Event<CloudEvent> cloudEventSink`.
After each reconciliation cycle, `TenantLoop.emitCycleEvents()` builds `CloudEvent` objects via
`ReconciliationEventEmitter` and dispatches them via `cloudEventSink::fire`. Observers use
`@ObservesAsync CloudEvent event` and filter by `event.getType()`. Data is deserialized from
`event.getData().toBytes()` using Jackson.

**Drift signal bridge** (`ApplicationLifecycleService`):

```java
void onCloudEvent(@ObservesAsync CloudEvent event) {
    if (!DesiredStateEventTypes.NODE_DRIFTED.equals(event.getType())) return;
    NodeDriftedData data = objectMapper.readValue(event.getData().toBytes(), NodeDriftedData.class);
    // correlate tenancyId to application, build DriftReport, signal case
}
```

1. Filters for `DesiredStateEventTypes.NODE_DRIFTED` (`"io.casehub.desiredstate.node.drifted"`)
2. Deserializes `NodeDriftedData(tenancyId, nodeId, nodeType, graphVersion, parentNodeId)`
3. Correlates `tenancyId` (composite key `appId:clusterId`) back to the application
4. Builds `DriftReport` with field-level details via `K8sDriftDiffService` (see §E.1)
5. Signals the application case: `runtime.signal(appCaseId, "driftDetected", report)` — the
   signal writes the `DriftReport` as the value at context path `driftDetected`. The binding's
   `SubCase.inputMapping(".driftDetected")` passes this value as child case input data.

### E.1. Field-Level Drift Diff

`NodeDriftedData` carries only `nodeId` — no field-level information. `K8sResourceHandler.readStatus()`
returns `NodeStatus` (enum), not a diff. But `K8sDeploymentHandler.managedFieldsMatch()` already
compares individual fields (replicas, image, env, resources, ports, probes) — it just returns boolean.

New `K8sDriftDiffService` (in `app/.../k8s/`):

1. Given a `nodeId`, looks up the `DesiredNode` from the current `DesiredStateGraph` (held by
   `ApplicationLifecycleService` per application)
2. Unwraps the `InfraDesiredNodeSpec` → `InfraNodeSpec` (e.g., `K8sDeploymentSpec`)
3. Calls a new `readDiff(KubernetesClient client, S spec)` method on `K8sResourceHandler`
   that returns `List<FieldDrift>` instead of boolean
4. `K8sDeploymentHandler.readDiff()` refactors the existing `managedFieldsMatch()` comparison
   logic to emit `FieldDrift(fieldName, expectedValue, actualValue)` for each mismatch instead
   of returning false on first mismatch

This is a mechanical refactor of existing comparison code — the field-level comparison logic
already exists in `managedFieldsMatch()`, it just needs to produce structured output.

**Race condition:** The actual state might change between drift detection (in the reconciliation
cycle) and the diff query (in the bridge). This is acceptable — the reconciliation loop will
re-provision anyway. If the diff query finds no differences (drift already corrected), the
`DriftReport` will have empty `driftDetails` and the classify worker will treat it as benign.

### F. Convergence Detection

A new `DriftConvergenceHandler` (analogous to `DeploymentOutcomeTracker` and
`DecommissionCompletionHandler`) observes `NODE_RECOVERED` CloudEvents:

```java
void onCloudEvent(@ObservesAsync CloudEvent event) {
    if (!DesiredStateEventTypes.NODE_RECOVERED.equals(event.getType())) return;
    NodeRecoveredData data = objectMapper.readValue(event.getData().toBytes(), NodeRecoveredData.class);
    // check if this nodeId is tracked, mark recovered, check all-converged
}
```

1. After a drift-remediation child case is spawned, `ApplicationLifecycleService` registers
   the child case ID with `DriftConvergenceHandler` along with the set of drifted node IDs
2. `DriftConvergenceHandler` observes `NODE_RECOVERED` CloudEvents
   (`DesiredStateEventTypes.NODE_RECOVERED` = `"io.casehub.desiredstate.node.recovered"`)
3. `NodeRecoveredData(tenancyId, nodeId, nodeType, graphVersion, parentNodeId)` provides
   per-node recovery — the reconciliation loop already emits these for every node that
   transitions from DRIFTED back to PRESENT
4. When a `NODE_RECOVERED` event arrives for a tracked node, mark that node as recovered
5. When all nodes for a case have recovered: signal `remediationStatus = "converged"` to
   the drift-remediation child case via `CaseHubRuntime.signal()`
6. On convergence, deregister the tracking entry and reset the consecutive drift counter

**Why NODE_RECOVERED, not RECONCILIATION_COMPLETED:** `ReconciliationCompletedData` has only
aggregate counts (`nodeCount`, `additionsCount`, `faultCount`) — no per-node status. The
handler cannot determine whether specific tracked nodes have recovered from aggregate data.
`NODE_RECOVERED` gives exactly the per-node granularity needed.

**Correlation:** Multiple drift cases can be open simultaneously. `DriftConvergenceHandler`
maintains a `ConcurrentHashMap<UUID, Set<String>>` mapping child case IDs to their pending
(not yet recovered) node ID sets. Each recovery event removes the node from the pending set.
When the pending set is empty, the case has converged.

### G. Consecutive Drift Count Tracking

`ApplicationLifecycleService` maintains a `ConcurrentHashMap<String, DriftTracker>` keyed
by application ID:

```java
record DriftTracker(Set<String> lastDriftedNodeIds, int consecutiveCount) {}
```

- When drift is detected: compare current drifted node IDs against `lastDriftedNodeIds`
  - If ANY overlap with the previous set → increment `consecutiveCount`
  - If completely disjoint → new drift event, reset `consecutiveCount` to 1
- When convergence is detected: reset tracker for that application
- This state is in-memory — restart resets counters. This is acceptable: post-restart,
  the first reconciliation cycle detects any existing drift as `consecutiveCount = 1`
  (benign). If it persists, subsequent cycles increment normally. Same convergence
  behavior, just delayed escalation by one cycle.

The "overlap" approach (rather than exact-set match) handles the case where node A and B
drift in cycle 1, then A, B, and C drift in cycle 2 — this IS escalation (the problem
is growing), not a new independent drift event.

## File Summary

### New files

| File | Purpose |
|------|---------|
| `app/.../case_/ApplicationCaseDescriptor.java` | Parent case definition builder |
| `app/.../case_/DriftRemediationCaseDescriptor.java` | Drift child case definition builder |
| `app/.../case_/StubChildCaseDescriptor.java` | Shared stub builder for 5 non-drift child cases |
| `app/.../case_/CaseDefinitionRegistrar.java` | `@Startup` bean — registers all case definitions |
| `app/.../model/DriftReport.java` | Drift report record with per-node field diffs |
| `app/.../model/FieldDrift.java` | Single field drift (expected vs actual) |
| `app/.../model/NodeDrift.java` | Per-node drift details |
| `app/.../service/DriftConvergenceHandler.java` | Observes NODE_RECOVERED CloudEvents, signals convergence to child cases |
| `app/.../k8s/K8sDriftDiffService.java` | Builds field-level diffs using K8sResourceHandler.readDiff() |

### Modified files

| File | Change |
|------|--------|
| `app/.../k8s/K8sClientRegistry.java` | Inject CredentialResolver, add credentialRef+trustCerts to register() |
| `app/.../k8s/K8sResourceHandler.java` | Add `readDiff(KubernetesClient, S spec)` returning `List<FieldDrift>` |
| `app/.../k8s/K8sDeploymentHandler.java` | Implement `readDiff()` — refactor `managedFieldsMatch()` to emit FieldDrift records |
| `app/.../entity/ClusterReferenceEntity.java` | Add `trustCerts` boolean field |
| `app/.../entity/ApplicationEntity.java` | Add `caseId` UUID field (nullable — null = DRAFT) |
| `app/.../service/ApplicationLifecycleService.java` | Start case on deploy (startCase → store caseId), observe NODE_DRIFTED CloudEvents, signal case, track consecutive drift |
| `app/.../service/StartupRecoveryService.java` | Update `clusterRegistrar` interface to accept credentialRef+trustCerts; use @Priority(20) |

### Unchanged files (previously listed as modified — corrected per review)

| File | Why unchanged |
|------|---------------|
| `app/.../rest/dto/RegisterClusterRequest.java` | Already has `credentialRef` field |
| `app/.../rest/ClusterResource.java` | Already passes `credentialRef` through |
| `app/.../service/ClusterService.java` | Only persists entity — does not call `clientRegistry.register()` |

### Test files (new)

| File | Coverage |
|------|----------|
| `app/.../case_/ApplicationCaseDescriptorTest.java` | Case definition structure, 6 bindings, JQ trigger expressions |
| `app/.../case_/DriftRemediationCaseDescriptorTest.java` | Worker count, capabilities, completion predicate |
| `app/.../case_/DriftClassifyWorkerTest.java` | Classification: benign (single node, first occurrence), critical (persistent, security fields, multi-node) |
| `app/.../case_/DriftRemediateWorkerTest.java` | Benign path (auto-remediate status), critical path (escalation flag) |
| `app/.../case_/DriftEscalateWorkerTest.java` | WorkItem creation with drift details |
| `app/.../case_/CaseDefinitionRegistrarTest.java` | All 7 definitions registered on startup |
| `app/.../model/DriftReportTest.java` | Record construction, driftedNodeIds(), hasSecuritySensitiveFields() |
| `app/.../k8s/K8sClientRegistryTest.java` | Extended: bearer token, user/pass, empty map with warning, null ref, trustCerts=false |
| `app/.../service/DriftConvergenceHandlerTest.java` | NODE_RECOVERED observation, multi-case correlation, deregistration |
| `app/.../k8s/K8sDriftDiffServiceTest.java` | Field-level diff generation from handler readDiff() |
| `app/.../service/ApplicationLifecycleServiceTest.java` | Extended: case creation on deploy, NODE_DRIFTED CloudEvent observation, consecutive count tracking |

## Implementation Order

1. **#42** — close issue (no code change)
2. **#44** — credential resolver wiring (isolated, no dependencies on #36)
3. **#36 foundation** — DriftReport/FieldDrift/NodeDrift models, ApplicationCaseDescriptor, StubChildCaseDescriptor
4. **#36 registration** — CaseDefinitionRegistrar (@Startup bean)
5. **#36 drift case** — DriftRemediationCaseDescriptor with all three workers
6. **#36 integration** — ApplicationLifecycleService signal bridge (reconciliation events → case signals)
7. **#36 convergence** — DriftConvergenceHandler, consecutive drift tracking

## Deferred Items (tracking issues to file)

- Credential rotation / expiration handling in `K8sClientRegistry` (lazy re-resolve on auth failure)
