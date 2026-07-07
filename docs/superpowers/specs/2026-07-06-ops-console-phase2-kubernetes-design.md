# Ops Console Phase 2: Kubernetes Integration — Design Spec

**Issue:** #29 (Epic: Service lifecycle management)
**Phase:** 2 of 5
**Depends on:** Phase 1 foundation (complete — 10 commits, 182 tests)
**Related issues:** #40 (active EventSource, deferred), #41 (InfraBackend.readState flaw)

## Goal

Replace the four `@DefaultBean` SPI stubs in `app/spi/` with real Kubernetes-backed
implementations using fabric8 7.5.2. After Phase 2, the ops console can deploy, monitor,
and reconcile K8s microservice topologies against real clusters (or mock servers in tests).

## Non-Goals

- Active K8s Watch integration (#40 — deferred, separate issue)
- Approval workflow for provisioning (Phase 3)
- Case lifecycle / child cases (Phase 3)
- UI (Phase 4)
- Real cluster demo (Phase 5)

---

## Architecture

### Why Not InfraBackend

The infra module delegates to `InfraBackend` by backendId. The app module does NOT
use this pattern for three reasons:

1. **`readState(NodeId)` is a design flaw (#41).** The signature takes only NodeId — no spec.
   A K8s backend needs namespace, name, and resource kind to query the API. The StandaloneBackend
   works because it stores state in a ConcurrentHashMap; any external-state backend cannot.

2. **Dynamic cluster registration.** InfraActualStateAdapter collects backends via
   `@Inject Instance<InfraBackend>` at construction. Clusters registered at runtime via REST
   aren't CDI beans. The app needs a mutable registry.

3. **Unnecessary layers.** InfraBackend was designed for multi-backend dispatch (Terraform/Ansible/K8s).
   The app module has one backend type (Kubernetes). InfraProvisionContext carries approval-specific
   fields (thresholds, approvedPlan, phase) unused in Phase 2. Every abstraction layer must be
   load-bearing.

### Chosen Pattern: K8sResourceHandler

Per-resource-type handlers that encapsulate the spec→fabric8 mapping. Shared by both the
ActualStateAdapter (for status reads) and the NodeProvisioner (for apply/delete).

```
ReconciliationLoop
  ├── KubernetesActualStateAdapter ──┐
  │                                   ├── K8sResourceHandler (per type)
  ├── KubernetesNodeProvisioner ─────┘        │
  │                                     K8sClientRegistry
  ├── KubernetesFaultPolicy                   │
  └── KubernetesEventSource              fabric8 client
      (passive)
```

Multi-cluster routing: `InfraDesiredNodeSpec.backendId()` encodes the cluster
(`"kubernetes:ops-prod"`). SPI implementations extract the clusterId, get a
`KubernetesClient` from `K8sClientRegistry`, then delegate to the handler.

---

## Component Design

### K8sResourceHandler

```java
package io.casehub.ops.app.k8s;

public interface K8sResourceHandler<S extends InfraNodeSpec> {
    Class<S> specType();
    HasMetadata toResource(S spec);
    NodeStatus readStatus(KubernetesClient client, S spec);
    void apply(KubernetesClient client, S spec);
    void delete(KubernetesClient client, S spec);
}
```

**Five implementations:**

| Handler | Spec | fabric8 Resource | Managed fields for drift |
|---------|------|-----------------|------------------------|
| `K8sNamespaceHandler` | `K8sNamespaceSpec` | `Namespace` | name, labels |
| `K8sDeploymentHandler` | `K8sDeploymentSpec` | `Deployment` | image, replicas, resources, ports, env, healthCheck |
| `K8sServiceHandler` | `K8sServiceSpec` | `Service` | port, targetPort, serviceType, selector labels |
| `K8sIngressHandler` | `K8sIngressSpec` | `Ingress` | host, rules |
| `K8sConfigMapHandler` | `K8sConfigMapSpec` | `ConfigMap` | data entries |

`readStatus()` queries the live K8s resource and compares it against the output of
`toResource(spec)`. This structural approach uses `toResource()` as the single source of
truth for managed fields — if a field is set by `toResource()`, it is automatically included
in drift comparison. K8s-internal fields (status, metadata.resourceVersion, controller
annotations, scheduler metadata) are ignored. Returns PRESENT/ABSENT/DRIFTED/UNKNOWN.

`apply()` uses `client.resource(toResource(spec)).serverSideApply()` with field manager
`"casehub-ops"` (constant across all instances and restarts). For fields managed by other
controllers (e.g., HPA modifying `replicas`), SSA uses force-apply semantics — casehub-ops
owns all fields it declares. Conflicts are resolved by casehub-ops taking ownership, since
the desired-state graph is authoritative. Falls back to
`client.resource(toResource(spec)).createOr(NonDeletingOperation::update)` where SSA is
not supported.

All handlers `@ApplicationScoped`, discovered via CDI and indexed by `specType()`.

### K8sClientRegistry

```java
@ApplicationScoped
public class K8sClientRegistry {
    private final ConcurrentHashMap<String, KubernetesClient> clients = new ConcurrentHashMap<>();

    public KubernetesClient clientFor(String clusterId);
    public void register(String clusterId, String apiUrl);
    public void deregister(String clusterId);

    @PreDestroy
    void shutdown() {
        clients.values().forEach(KubernetesClient::close);
        clients.clear();
    }
}
```

- Creates `KubernetesClient` from cluster data (apiUrl only — no default namespace).
  Namespace is a per-resource attribute, not a per-client binding. Handlers read namespace
  from the `InfraNodeSpec`, which already carries it. `ClusterReferenceEntity.namespace`
  remains as the default deployment namespace for goal compilation (used by
  `ApplicationGoalCompiler`), not a client-level configuration.
- Phase 2 credentials: fabric8 auto-detection (in-cluster ServiceAccount or `~/.kube/config`).
  `credentialRef` stored on `ClusterReferenceEntity` but not resolved — pluggable credential
  resolver is a future concern (#44).
- Thread-safe via ConcurrentHashMap. Clients are long-lived, closed on deregister.
- `@PreDestroy shutdown()` closes all clients on application shutdown, preventing HTTP
  connection and thread pool leaks.
- **Deregistration contract:** `deregister()` must only be called after all reconciliation
  loops referencing that cluster have been stopped. `ClusterService.delete()` enforces
  this by querying `ApplicationLifecycleService.hasActiveLoopsForCluster(clusterId)` — if
  any loops reference the cluster, the request is rejected with HTTP 409 Conflict.
- **Active loop index:** `ApplicationLifecycleService` maintains a
  `ConcurrentHashMap<String, Set<String>>` mapping clusterId → active composite keys.
  `trackLoopKey(clusterId, compositeKey)` adds entries; `removeLoopKey(compositeKey)` removes
  them. Updated by `deploy()` (adds key via `trackLoopKey`), `StartupRecoveryService`
  (populates on boot via `trackLoopKey`), and `DecommissionCompletionHandler` (removes key
  via `removeLoopKey` when decommission converges). This is an app-module concern — no API
  changes to `ReconciliationLoop` are needed, since the app module creates the composite
  keys and owns the key structure.

### K8sHandlerRegistry

```java
@ApplicationScoped
public class K8sHandlerRegistry {
    private final Map<Class<? extends InfraNodeSpec>, K8sResourceHandler<?>> handlers;

    @Inject
    K8sHandlerRegistry(Instance<K8sResourceHandler<?>> discovered) {
        this.handlers = discovered.stream()
            .collect(toMap(K8sResourceHandler::specType, h -> h));
    }

    @SuppressWarnings("unchecked")
    public <S extends InfraNodeSpec> K8sResourceHandler<S> handlerFor(Class<S> specType);
}
```

---

## SPI Implementations

### KubernetesActualStateAdapter

Replaces `StubActualStateAdapter`. `@ApplicationScoped` (no `@DefaultBean`).

```
readActual(DesiredStateGraph desired, String tenancyId):
  For each DesiredNode in desired.nodes().values():
    1. Cast spec to InfraDesiredNodeSpec → get backendId, resourceSpec
    2. Extract clusterId from backendId ("kubernetes:X" → "X")
    3. KubernetesClient client = clientRegistry.clientFor(clusterId)
    4. K8sResourceHandler handler = handlerRegistry.handlerFor(resourceSpec.getClass())
    5. NodeStatus status = handler.readStatus(client, resourceSpec)
    6. Collect into Map<NodeId, NodeStatus>
  Return new ActualState(statuses)
```

On error (cluster unreachable, API error): return `NodeStatus.UNKNOWN` for that node,
continue with remaining nodes.

### KubernetesNodeProvisioner

Replaces `StubNodeProvisioner`. `@ApplicationScoped` (no `@DefaultBean`).

```
handledTypes(): K8S_NAMESPACE, K8S_DEPLOYMENT, K8S_SERVICE, K8S_INGRESS, K8S_CONFIGMAP
resyncInterval(): Duration.ofMinutes(5)

provision(DesiredNode node, ProvisionContext context):
  1. Cast spec to InfraDesiredNodeSpec
  2. Extract clusterId, get client, find handler
  3. handler.apply(client, resourceSpec)
  4. Return ProvisionResult.Success() or Failed(reason)

deprovision(DesiredNode node, DeprovisionContext context):
  Same dispatch → handler.delete(client, resourceSpec)
  Return DeprovisionResult.Success() or Failed(reason)
```

### KubernetesFaultPolicy

Replaces `StubFaultPolicy`. `@ApplicationScoped` (no `@DefaultBean`).

```
onFault(FaultEvent event, DesiredStateGraph current, ActualState actual):
  return List.of()
```

Returns empty — runtime handles retry, re-provisioning, and escalation.
Same as infra module's PoC approach. K8s-aware fault responses (removing persistently
failing resources from the graph) require operational feedback that doesn't exist yet.

### KubernetesEventSource

Replaces `StubEventSource`. `@ApplicationScoped` (no `@DefaultBean`).

Passive — same architecture as `InfraEventSource`:
- Hot `Multi<StateEvent>` backed by `MultiEmitter` with broadcast to all subscribers
- `emit(StateEvent)` and `emitDrift(NodeId)` for external callers
- No internal Watch subscriptions (deferred to #40)

Drift detection relies on ReconciliationLoop's periodic resync (5 min default).

---

## Startup Recovery

### StartupRecoveryService

```java
@ApplicationScoped
public class StartupRecoveryService {
    @Inject ReconciliationLoop reconciliationLoop;
    @Inject ApplicationGoalCompiler goalCompiler;
    @Inject DesiredStateGraphFactory graphFactory;
    @Inject ClusterService clusterService;
    @Inject K8sClientRegistry clientRegistry;
    @Inject ApplicationLifecycleService lifecycleService;
    @Inject DecommissionCompletionHandler decommissionHandler;

    void onStartup(@Observes StartupEvent event) {
        // 1. Register all known clusters in K8sClientRegistry
        List<ClusterReferenceEntity> clusters = ClusterReferenceEntity.listAll();
        for (var cluster : clusters) {
            clientRegistry.register(cluster.id.toString(), cluster.apiUrl);
        }

        // 2. Find non-terminal applications (including DECOMMISSIONING)
        List<ApplicationEntity> active = ApplicationEntity.list(
            "status in (?1)",
            List.of(ApplicationStatus.DEPLOYING, ApplicationStatus.RUNNING,
                     ApplicationStatus.DEGRADED, ApplicationStatus.DECOMMISSIONING));

        // 3. Restart reconciliation loops and populate active loop index
        for (var app : active) {
            List<ClusterReferenceEntity> appClusters = clusterService.list(app.tenancyId);
            boolean decommissioning = app.status == ApplicationStatus.DECOMMISSIONING;
            Set<String> compositeKeys = new HashSet<>();

            for (var cluster : appClusters) {
                String key = app.tenancyId + ":" + app.id + ":" + cluster.id;
                DesiredStateGraph graph;
                if (decommissioning) {
                    graph = graphFactory.of(List.of(), List.of());
                } else {
                    List<ServiceDefinition> services = parseServices(app.servicesJson);
                    graph = goalCompiler.compileForCluster(
                        services, cluster.id.toString(), cluster.namespace, graphFactory);
                }
                reconciliationLoop.start(key, graph);
                lifecycleService.trackLoopKey(cluster.id.toString(), key);
                compositeKeys.add(key);
            }

            if (decommissioning) {
                decommissionHandler.registerDecommission(app.id, compositeKeys);
            }
        }
    }
}
```

The first reconciliation cycle reads actual K8s state and converges.

---

## ApplicationLifecycleService Changes

Replace Phase 1 TODO placeholders with real ReconciliationLoop calls.

**Composite key:** `tenancyId + ":" + app.id + ":" + cluster.id`. The key must include
applicationId because a tenant may deploy multiple applications to the same cluster —
without it, `start()` would collide on the second application.

**Start-or-update semantics:** `deploy()` must handle the case where a loop is already
running for the composite key (e.g., started by `StartupRecoveryService` during a race
at boot). Pattern: attempt `start()`, catch `IllegalStateException`, fall back to
`updateDesired()`. This makes both startup recovery and runtime deploy safe without
coordination.

**Deployment outcome:** `recordDeployment()` records `DeploymentOutcome.PENDING` (not
SUCCESS). The deployment record is updated to SUCCESS or FAILED asynchronously by
`DeploymentOutcomeTracker`.

```java
// deploy() — cancel any in-progress decommission before starting:
decommissionHandler.cancelDecommission(app.id);

// deploy() — after goal compilation, per cluster:
String key = tenancyId + ":" + app.id + ":" + cluster.id;
try {
    reconciliationLoop.start(key, graph);
} catch (IllegalStateException e) {
    reconciliationLoop.updateDesired(key, graph);
}
trackLoopKey(cluster.id.toString(), key);  // populate active loop index

// decommission() — per cluster:
Set<String> compositeKeys = new HashSet<>();
for (var cluster : clusters) {
    String key = tenancyId + ":" + app.id + ":" + cluster.id;
    var emptyGraph = graphFactory.of(List.of(), List.of());
    reconciliationLoop.updateDesired(key, emptyGraph);
    compositeKeys.add(key);
}
decommissionHandler.registerDecommission(app.id, compositeKeys);

// update topology — when services change:
reconciliationLoop.updateDesired(tenancyId + ":" + app.id + ":" + cluster.id, newGraph);
```

`recordDeployment(app, DeploymentTrigger.INITIAL, DeploymentOutcome.PENDING)` is called
after starting/updating loops. Outcome is updated asynchronously from reconciliation events.
Loop stop, active loop index cleanup, and DECOMMISSIONED transition are handled by
`DecommissionCompletionHandler` on convergence.

Also: register clusters in K8sClientRegistry when they're created via ClusterService.

### DeploymentOutcomeTracker

`@ApplicationScoped` bean that tracks cross-cluster convergence for PENDING deployments
and transitions their outcome to SUCCESS or FAILED.

```java
@ApplicationScoped
public class DeploymentOutcomeTracker {
    // deploymentId → (clusterId → converged)
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>> tracking
        = new ConcurrentHashMap<>();

    void registerDeployment(UUID deploymentId, Set<String> clusterIds);
    void onCloudEvent(@ObservesAsync CloudEvent event);
}
```

**CDI observer:** The observer receives `CloudEvent` (not `ReconciliationCompletedData`)
because `ReconciliationLoop` fires events via CDI `Event<CloudEvent>` — the
`ReconciliationEventEmitter` wraps data records in CloudEvent envelopes. The handler
filters by event type `io.casehub.desiredstate.reconciliation.completed` and deserializes
the `ReconciliationCompletedData` from the CloudEvent data payload.

**Registration:** `deploy()` calls `tracker.registerDeployment(deploymentId, clusterIds)` after
creating the PENDING DeploymentRecordEntity. The tracker initializes a per-cluster convergence
map with all entries set to `false`.

**Convergence detection:** The handler parses the composite key from the event's `tenancyId`
field (`tenancyId:appId:clusterId`), looks up the PENDING deployment for that application,
and marks the cluster as converged when `additionsCount == 0 && faultCount == 0`. When all
clusters for a deployment are converged, the tracker updates the `DeploymentRecordEntity`
outcome to `SUCCESS`.

**Timeout:** A `@Scheduled(every = "1m")` method checks for PENDING deployments older than
the deployment timeout (configurable, default 10 minutes via
`casehub.ops.deployment.timeout`). Deployments past the timeout transition to `FAILED`.
The timeout is the definition of "persistent fault" — no cycle counting or heuristics.

**Restart resilience:** `@Observes StartupEvent` re-registers tracking for all
`DeploymentRecordEntity` rows with `outcome == PENDING`. The set of cluster IDs is
derived from the composite keys of active reconciliation loops for that application (via
`ApplicationLifecycleService.activeLoopKeysForApp()`). After startup recovery re-starts
all loops, the first reconciliation cycle emits CloudEvents that rebuild convergence state
naturally.

**Lifecycle:** Tracking entries are removed when the outcome transitions to SUCCESS or FAILED.
No unbounded growth.

### DecommissionCompletionHandler

`@ApplicationScoped` bean that detects when decommissioned applications have fully
converged on an empty graph, then stops their loops and cleans up.

```java
@ApplicationScoped
public class DecommissionCompletionHandler {
    @Inject ReconciliationLoop reconciliationLoop;
    @Inject ApplicationLifecycleService lifecycleService;

    // appId → Set<compositeKey>
    private final ConcurrentHashMap<UUID, Set<String>> tracking = new ConcurrentHashMap<>();

    void registerDecommission(UUID appId, Set<String> compositeKeys);
    void cancelDecommission(UUID appId);
    void onCloudEvent(@ObservesAsync CloudEvent event);
}
```

**Registration:** `decommission()` calls `handler.registerDecommission(appId, compositeKeys)`
after updating desired graphs to empty.

**Cancellation:** `deploy()` calls `handler.cancelDecommission(appId)` before starting or
updating loops. This removes the tracking entry without stopping loops or transitioning
status — the loops are still running (with the empty graph from the prior decommission) and
`deploy()` will immediately `updateDesired()` them with the real graph. Without this,
`DecommissionCompletionHandler` would fire on the next clean reconciliation cycle and stop
the loop that `deploy()` just re-activated.

**Convergence detection:** The `@ObservesAsync CloudEvent` handler filters for
`io.casehub.desiredstate.reconciliation.completed` events. For tracked decommission keys,
convergence is detected when `removalsCount == 0 && faultCount == 0` — the empty graph has
no more resources to deprovision and no failures. On convergence of a key:
1. Call `reconciliationLoop.stop(compositeKey)` — stops the loop, frees scheduler slot
2. Call `lifecycleService.removeLoopKey(compositeKey)` — removes from the active loop index
3. When all keys for an application are converged and stopped, update `ApplicationEntity`
   status to `DECOMMISSIONED`

**Timeout:** Same `@Scheduled(every = "1m")` pattern as `DeploymentOutcomeTracker`. If
decommission doesn't converge within the timeout (configurable, default 10 minutes via
`casehub.ops.decommission.timeout`), the handler force-stops remaining loops and transitions
to `DECOMMISSIONED` anyway — a decommissioned application should not block cluster
deregistration indefinitely.

**Restart resilience:** `@Observes StartupEvent` re-registers tracking for all
`ApplicationEntity` rows with `status == DECOMMISSIONING`. `StartupRecoveryService` already
starts loops for DECOMMISSIONING apps (they're non-terminal), and uses empty graphs. The
handler re-registers the keys and convergence detection resumes.

**Lifecycle:** Tracking entries are removed after all keys are stopped and the app transitions
to DECOMMISSIONED.

---

## Testing Strategy

### Unit tests per handler
Mock `KubernetesClient` or use fabric8's lightweight `KubernetesServer`.
Each handler tested independently:
- `toResource()` builds correct fabric8 object from spec
- `readStatus()` returns PRESENT when resource matches, DRIFTED when fields differ, ABSENT when missing
- `apply()` creates the resource, idempotent on re-apply
- `delete()` removes the resource, no-op when absent

### SPI integration tests
`@QuarkusTest` with `kubernetes-server-mock`. Full cycle:
- Create application → deploy → verify resources created in mock cluster
- Externally modify resource → trigger resync → verify drift detected and corrected
- Decommission → verify resources deleted

### Startup recovery test
Pre-populate `ApplicationEntity` in RUNNING state, invoke `StartupRecoveryService.onStartup()`
directly (or via Quarkus test lifecycle), verify ReconciliationLoops restarted and
initial reconciliation runs.

### K8sClientRegistry test
Register/deregister clusters, verify client lifecycle, verify `clientFor()` throws on
unknown clusterId.

### Deploy start-or-update semantics test
Verify that `deploy()` succeeds when a loop is already running for the composite key
(simulating a startup recovery race): pre-start a loop via `ReconciliationLoop.start()`,
then call `deploy()` — verify it falls through to `updateDesired()` and the graph is
updated without exception.

### Deployment outcome tracking test
Verify the full `DeploymentOutcomeTracker` lifecycle:
- `deploy()` creates a PENDING DeploymentRecordEntity
- Emit `ReconciliationCompletedData` with `additionsCount=0, faultCount=0` for each
  cluster → verify record transitions to SUCCESS
- Emit `ReconciliationCompletedData` with `faultCount > 0` and wait past timeout →
  verify record transitions to FAILED
- Restart resilience: pre-populate PENDING records, invoke startup recovery, emit clean
  reconciliation events → verify records transition to SUCCESS

### Decommission completion test
Verify the full decommission lifecycle:
- `decommission()` submits empty graph and registers with `DecommissionCompletionHandler`
- Emit clean `ReconciliationCompletedData` events (`removalsCount=0, faultCount=0`) →
  verify `reconciliationLoop.stop()` called for each key, active loop index entries removed,
  ApplicationEntity status transitions to DECOMMISSIONED
- Timeout: delay convergence past timeout → verify loops force-stopped and status transitions
- Startup resilience: pre-populate DECOMMISSIONING apps, invoke startup recovery → verify
  loops started with empty graphs and decommission tracking re-registered
- Zombie prevention: after decommission completes, verify `hasActiveLoopsForCluster()`
  returns false for the cluster (unblocking deregistration)

### Deploy-during-decommission test
Verify that re-deploying a DECOMMISSIONING application cancels decommission tracking and
proceeds correctly:
- `decommission()` → register with `DecommissionCompletionHandler` → verify tracking active
- `deploy()` → verify `cancelDecommission()` called → tracking removed
- Reconciliation converges with real graph → verify `DecommissionCompletionHandler` does NOT
  fire (no loop stop, no DECOMMISSIONED transition)
- Verify `DeploymentOutcomeTracker` fires correctly → record transitions to SUCCESS
- Verify ApplicationEntity status is DEPLOYING/RUNNING, not DECOMMISSIONED

### Cluster deregistration rejection test
Verify `ClusterService.delete()` returns HTTP 409 when reconciliation loops are active
for the target cluster. Verify it succeeds after all loops for that cluster are stopped.

---

## Package Layout

```
app/src/main/java/io/casehub/ops/app/
├── k8s/
│   ├── K8sResourceHandler.java           (interface)
│   ├── K8sHandlerRegistry.java           (CDI lookup by spec type)
│   ├── K8sClientRegistry.java            (clusterId → KubernetesClient)
│   ├── K8sNamespaceHandler.java
│   ├── K8sDeploymentHandler.java
│   ├── K8sServiceHandler.java
│   ├── K8sIngressHandler.java
│   ├── K8sConfigMapHandler.java
│   ├── KubernetesActualStateAdapter.java
│   ├── KubernetesNodeProvisioner.java
│   ├── KubernetesFaultPolicy.java
│   └── KubernetesEventSource.java
├── service/
│   ├── StartupRecoveryService.java       (new)
│   ├── DeploymentOutcomeTracker.java      (new)
│   ├── DecommissionCompletionHandler.java (new)
│   └── ApplicationLifecycleService.java   (modified)
└── spi/
    ├── StubActualStateAdapter.java       (unchanged — @DefaultBean fallback)
    ├── StubNodeProvisioner.java          (unchanged)
    ├── StubFaultPolicy.java              (unchanged)
    ├── StubEventSource.java              (unchanged)
    └── FaultPolicyListProducer.java      (unchanged)
```

Phase 1 stubs preserved as `@DefaultBean` fallbacks. Real implementations in `k8s/` are
plain `@ApplicationScoped` — CDI gives them priority over `@DefaultBean` automatically.

---

## Deferred / Out of Scope

| Item | Issue | Why |
|------|-------|-----|
| Active EventSource (fabric8 Watch) | #40 | Separable complexity, resync handles correctness |
| InfraBackend.readState(NodeId) fix | #41 | Cross-cutting API change, not needed for app module |
| FaultPolicy 2→3 arg migration (infra, deployment, compliance, iot modules) | #42 | API evolved; domain modules still use 2-arg signature |
| Approval workflow for provisioning (Phase 3) | #43 | Requires WorkItem integration |
| Pluggable credential resolver for clusters | #44 | Phase 2 uses fabric8 auto-detection |
| K8s-aware FaultPolicy responses | #45 | Needs operational feedback from failure history |
