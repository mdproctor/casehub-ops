# Ops Console Application — Design Spec

Issue: #29, #30
Epic: #29 (Service lifecycle management)

## Summary

A standalone Quarkus web application (`app/` module in casehub-ops) for deploying and managing
software infrastructure through its full lifecycle. Embeds the CaseHub engine, desired-state
runtime, and its own desiredstate SPI implementations behind a wizard + dashboard UI built with
blocks-ui components.

Targets Kubernetes/OpenShift via fabric8. Uses the service-as-case model: one long-lived
Application Case per managed app, child cases spawned by events (CVEs, upgrades, incidents,
drift, compliance violations, scaling).

Scope: complete end-to-end skeleton — every API, model, and screen wired, key paths fully
working (deploy, CVE response, upgrade, drift remediation), remaining paths stubbed.

## Architecture

### SPI Implementation Strategy

The `app/` module is a **Quarkus application that implements the desiredstate SPI quad
directly**, rather than depending on a domain module. It provides its own `@ApplicationScoped`
implementations of all five desiredstate SPIs (GoalCompiler, ActualStateAdapter, NodeProvisioner,
FaultPolicy, EventSource). This avoids the single-domain CDI constraint (ARC42STORIES.MD §2)
because there are no domain modules on the classpath — the app provides its own SPI
implementations.

Note: `app/` is NOT a "domain module" in the ARC42STORIES sense — domain modules are Jandex
libraries with no REST endpoints, activated by classpath presence. The `app/` module is a
Quarkus application with REST endpoints that happens to implement the same SPI pattern. The
distinction matters: domain modules are consumed by applications; this IS the application.

The app module uses K8s spec types from `casehub-ops-api` (`K8sDeploymentSpec`,
`K8sServiceSpec`, `K8sIngressSpec`, `K8sNamespaceSpec`, `K8sConfigMapSpec`) and wraps them in
`InfraDesiredNodeSpec` for the desiredstate graph. The app module does NOT use the
`InfraBackend` SPI — this was revised in Phase 2 (see Phase 2 spec §"Why Not InfraBackend"
for rationale: readState(NodeId) design flaw, dynamic cluster registration, unnecessary
abstraction layers). Instead, the app module uses direct `K8sResourceHandler` dispatch.

This is the same relationship as between `DeploymentGoalCompiler` and its foundation APIs:
the goal compiler is domain-specific, the spec types are shared.

### Embedded Stack

The `app/` module is a Quarkus application that embeds:

- **casehub-engine** — case lifecycle, workers, signals, bindings
- **casehub-engine-work-adapter** — WorkItem bridge for case-level approval (`ActionRiskClassifier` → `HumanTaskScheduleHandler` → WorkItem creation; `WorkItemLifecycleAdapter` routes approval/rejection back to the case)
- **casehub-desiredstate runtime** — reconciliation loop, transition planning, fault handling
- **casehub-ops-api** — shared K8s spec types, InfraBackend SPI, OpsPendingApprovalHandler
- **fabric8 kubernetes-client** — real K8s/OpenShift integration
- **casehub-pages** — standalone UI application framework (direct Quinoa serving, not iframe)
- **blocks-ui components** — Lit Web Components for the ops UI

Engine is embedded behind clean internal SPIs so it can be extracted to a separate service
later without rearchitecting.

### Layers

```
┌─────────────────────────────────────────────┐
│  UI (casehub-pages + blocks-ui components)  │
├─────────────────────────────────────────────┤
│  REST API (JAX-RS)                          │
├─────────────────────────────────────────────┤
│  Case Model (engine + bindings + workers)   │
├─────────────────────────────────────────────┤
│  Goal Compiler (Application → DesiredState) │
├─────────────────────────────────────────────┤
│  Reconciliation (desiredstate runtime)      │
├─────────────────────────────────────────────┤
│  K8sResourceHandler (direct dispatch)       │
├─────────────────────────────────────────────┤
│  fabric8 (K8s/OpenShift API)                │
└─────────────────────────────────────────────┘
```

### UI Integration — Standalone casehub-pages Application

The ops console app is a **standalone application**, not an iframe-embedded consumer. Unlike
claudony, drafthouse, devtown, life, and aml (which embed casehub-pages via iframe), this app
serves its own UI directly through Quarkus static resources with Quinoa build integration.

This is intentional: the ops console is the primary application — there is no host to embed
within. It uses casehub-pages' workbench primitives (split panels, dock bars, panel hosting),
data pipeline, and component API directly, served from `META-INF/resources/`.

`casehub-pages-runtime` is NOT needed — the app depends on `casehub-pages` for its TypeScript
framework and component API, built via Quinoa into static resources.

## Module Structure

### New in casehub-ops

```
app/
├── src/main/java/io/casehub/ops/app/
│   ├── rest/             # JAX-RS endpoints
│   ├── model/            # Application, Service, Cluster JPA entities + domain records
│   ├── service/          # ApplicationLifecycleService, ClusterService
│   ├── case/             # ApplicationCaseDescriptor, bindings, workers
│   ├── goal/             # ApplicationGoalCompiler
│   ├── k8s/              # KubernetesBackend, KubernetesActualStateAdapter
│   ├── k8s/resource/     # Per-resource-type provisioners
│   └── startup/          # CDI producers, engine bootstrap
├── src/main/resources/
│   ├── db/app/migration/ # Flyway migrations (V1__application.sql, V2__cluster.sql, etc.)
│   └── META-INF/resources/   # UI (Quinoa build output)
├── src/test/java/            # Tests with fabric8 mock server
└── pom.xml

examples/
├── quarkus-microservices/
│   ├── online-store.yaml     # 3-service sample topology
│   ├── README.md             # Demo walkthrough
│   └── scenarios/            # CVE, upgrade, scaling event scripts
```

### New blocks-ui components (in casehub/blocks-ui repo)

```
components/
├── ops-topology-view/        # Service dependency graph visualisation
├── ops-service-card/         # Service status card
├── ops-event-timeline/       # Lifecycle event timeline
├── ops-wizard/               # Multi-step deployment wizard shell
├── ops-cluster-selector/     # Cluster picker with status indicators
└── ops-approval-panel/       # Pending approval list with approve/reject
```

All components follow blocks-ui conventions: Lit 3.x, Shadow DOM, OKLCH design tokens,
container queries, `pages-event` CustomEvents, dual data mode (endpoint/data props).

### Maven dependencies for app/

- `casehub-engine-runtime`
- `casehub-engine-work-adapter`
- `casehub-desiredstate-runtime`
- `casehub-ops-api`
- `io.fabric8:kubernetes-client`
- `io.fabric8:kubernetes-server-mock` (test scope)

Not included (single-domain CDI constraint):
- ~~`casehub-ops-infra`~~ — the app implements the SPI quad directly
- ~~`casehub-ops-deployment`~~ — provisions CaseHub topology, not K8s workloads
- ~~`casehub-ops-compliance`~~ — a separate domain; compliance features in the app use
  the compliance api types from `casehub-ops-api`, not the domain module

## Data Model

### Application

Top-level managed entity. This is the APPLICATION-level input — what the user declares.

| Field | Type | Description |
|-------|------|-------------|
| `applicationId` | `String` | Unique identifier |
| `name` | `String` | Display name |
| `description` | `String` | Optional description |
| `tenancyId` | `String` | Tenant scope |
| `services` | `List<ServiceDefinition>` | Services in this app |
| `targetClusters` | `List<ClusterReference>` | Deployment targets |
| `compliancePolicies` | `List<CompliancePolicyRef>` | Applicable compliance checks |
| `status` | `ApplicationStatus` | DRAFT, DEPLOYING, RUNNING, DEGRADED, DECOMMISSIONING, DECOMMISSIONED |

### ServiceDefinition

One microservice in the topology. This is the application-level abstraction — the
`ApplicationGoalCompiler` transforms it into K8s infrastructure specs.

| Field | Type | Description |
|-------|------|-------------|
| `serviceId` | `String` | Unique within application |
| `name` | `String` | Display name |
| `image` | `String` | Container image reference (including tag, e.g. `quay.io/app:1.2.3`) |
| `replicas` | `int` | Desired replica count |
| `ports` | `List<PortMapping>` | Port mappings |
| `env` | `Map<String, String>` | Environment variables |
| `resources` | `ResourceRequirements` | CPU/memory requests and limits |
| `dependsOn` | `List<String>` | Service IDs this depends on |
| `healthCheck` | `HealthCheckSpec` | Liveness/readiness probes |
| `targetClusters` | `List<String>` | Cluster IDs where this service is deployed. Empty = all clusters. |

### Transformation boundary — ServiceDefinition → K8s specs

`ApplicationGoalCompiler` transforms each `ServiceDefinition` into infrastructure-level K8s
specs from `casehub-ops-api`:

| ServiceDefinition field | Target K8s spec | Mapping |
|------------------------|----------------|---------|
| `image`, `replicas`, `resources`, `env`, `healthCheck` | `K8sDeploymentSpec` | Direct mapping — K8sDeploymentSpec extended with `env`, `ports`, `healthCheck` fields (#39) |
| `ports` | `K8sServiceSpec` | One K8s Service per service, mapping port/targetPort |
| Ingress annotation on port | `K8sIngressSpec` | Optional — if port is marked external |
| Namespace from ClusterReference | `K8sNamespaceSpec` | Ensure-exists semantics |
| Environment from ConfigMap | `K8sConfigMapSpec` | New sealed permit (#39) |

The `ServiceDefinition.image` field uses standard container image reference format (`image:tag`),
not separate image/tag fields — this matches K8s conventions. The previous `tag` field is
removed; tag is part of the image reference.

### K8sDeploymentSpec extension

The existing `K8sDeploymentSpec` carries: `namespace`, `name`, `image`, `replicas`, `resources`,
`labels`. For the ops console app, it needs extension with:

- `ports` — container port declarations
- `env` — environment variable map
- `healthCheck` — liveness/readiness probe configuration

These are standard K8s Deployment concerns. The extension is tracked in #39 alongside
`K8sConfigMapSpec`.

### ClusterReference

A registered Kubernetes/OpenShift target.

| Field | Type | Description |
|-------|------|-------------|
| `clusterId` | `String` | Unique identifier |
| `name` | `String` | Display name |
| `apiUrl` | `String` | Kubernetes API URL |
| `namespace` | `String` | Target namespace |
| `credentials` | `CredentialRef` | Reference to credentials (not inline) |
| `type` | `ClusterType` | KUBERNETES or OPENSHIFT |
| `status` | `ClusterStatus` | CONNECTED, UNREACHABLE, UNKNOWN |

### DeploymentRecord

Point-in-time deployment snapshot.

| Field | Type | Description |
|-------|------|-------------|
| `deploymentId` | `String` | Unique identifier |
| `applicationId` | `String` | Parent application |
| `timestamp` | `Instant` | When deployed |
| `topology` | `Map<String, ServiceVersion>` | Version per service |
| `trigger` | `DeploymentTrigger` | INITIAL, UPGRADE, CVE_RESPONSE, ROLLBACK, SCALE |
| `outcome` | `DeploymentOutcome` | SUCCESS, PARTIAL, FAILED, PENDING_APPROVAL |

### CveEvent

Inbound CVE detection.

| Field | Type | Description |
|-------|------|-------------|
| `cveId` | `String` | CVE identifier |
| `severity` | `CveSeverity` | CRITICAL, HIGH, MEDIUM, LOW |
| `affectedImage` | `String` | Affected container image |
| `affectedServices` | `List<String>` | Service IDs affected |
| `fixedInTag` | `String` | Patched image tag (if known) |
| `source` | `String` | Scanner identity |

### Persistence model

The app requires a database for configuration and history that exists outside the engine's
case lifecycle.

| Entity | Persistence | Rationale |
|--------|------------|-----------|
| `Application` (DRAFT) | JPA entity, app database | Exists before any case — DRAFT applications have no case yet |
| `Application` (deployed) | Case blackboard (`topology` key) + JPA entity | Blackboard is runtime truth; JPA entity is the configuration record |
| `ClusterReference` | JPA entity, app database | Application-independent; must survive restarts |
| `DeploymentRecord` | JPA entity, app database | Point-in-time snapshots, queryable for deployment history and rollback |
| `CveEvent` | Case context only | Ingested CVE data becomes child case blackboard state; no separate persistence |

Flyway migrations at `db/app/migration/` (scoped to the app's named datasource per platform
convention — never inside `db/migration/`). H2 for demo/mock profiles; PostgreSQL for
production.

### ApplicationLifecycleService — deployment orchestration

`ApplicationLifecycleService` is the central coordination component that bridges REST, engine,
goal compiler, and reconciliation. Lives in `app/src/main/java/io/casehub/ops/app/service/`.

| Responsibility | Method | Flow |
|---------------|--------|------|
| Deploy | `deploy(applicationId)` | Load Application → create case via `CaseHubRuntime.startCase()` → `compileForCluster()` per cluster → `ReconciliationLoop.start()` per cluster |
| Update | `update(applicationId, updatedApp)` | Persist updated Application → update case blackboard → `compileForCluster()` per cluster → `ReconciliationLoop.updateDesired()` per cluster |
| Rollback | `rollback(applicationId, deploymentId)` | Load DeploymentRecord → restore topology → update as above |
| Decommission | `decommission(applicationId)` | Compile empty graph per cluster → `ReconciliationLoop.updateDesired(compositeKey, emptyGraph)` per cluster → TransitionPlanner creates DEPROVISION steps for all PRESENT nodes → TransitionExecutor deprovisions with fault handling → on convergence (nodeCount=0), signal case to COMPLETED → `ReconciliationLoop.stop()` per cluster |
| Status | `status(applicationId)` | Derive `ApplicationStatus` from case state + per-cluster reconciliation state |

This is the "app orchestrator" referenced in §Multi-cluster reconciliation model — now named
and placed.

### Startup recovery

ReconciliationLoop TenantLoops are in-memory (`ConcurrentHashMap`). On application restart
(crash, pod eviction, redeployment), all loops are lost. `ApplicationLifecycleService`
implements `@Startup` recovery:

1. Query all Applications with status in {DEPLOYING, RUNNING, DEGRADED, DECOMMISSIONING}
2. For each, read the current topology from the Application JPA entity (authoritative
   configuration) and verify against the case blackboard (runtime state)
3. Call `compileForCluster()` per cluster
4. Call `ReconciliationLoop.start(compositeKey, graph)` per cluster
5. The first reconciliation cycle detects and corrects any drift accumulated during downtime

For DECOMMISSIONING applications, startup recovery resubmits the empty graph — the
decommission flow resumes where it left off. For DECOMMISSIONED applications (case
COMPLETED), no loop is started.

This ensures the system is self-healing after restarts — the same reconciliation architecture
that handles initial deployment also handles recovery.

### ApplicationStatus — derived state machine

`ApplicationStatus` is **derived**, not independently maintained. It is computed by
`ApplicationLifecycleService.status()` from three sources:

1. **Case state** — from `CaseHubRuntime` (STARTING, WAITING, COMPLETED, FAULTED)
2. **Per-cluster reconciliation state** — from `ReconciliationLoop` (converged, in-progress, faulted)
3. **Active child case count** — from case blackboard `activeChildCases`

Derivation rules:

| Condition | ApplicationStatus |
|-----------|------------------|
| No case exists | DRAFT |
| Case STARTING, or case WAITING + any cluster not yet converged (initial deploy) | DEPLOYING |
| Case WAITING + all clusters converged + no active incident/drift child cases | RUNNING |
| Case WAITING + any cluster faulted, or active incident/drift/CVE child cases | DEGRADED |
| Decommission signal sent + case still WAITING (child cases closing, resources deprovisioning) | DECOMMISSIONING |
| Case COMPLETED | DECOMMISSIONED |
| Case FAULTED | maps to DEGRADED (FAULTED is terminal in engine; DEGRADED allows recovery via child cases) |

There is no independent `ApplicationStatus` field that needs synchronization — the REST API
computes it on each request. The `status` field in the Application JPA entity is a cached
snapshot updated on case state change events (CDI `@ObservesAsync`) for query efficiency
(list views), but the source of truth is always the derivation.

### Multi-cluster consistency model

**Per-cluster eventual consistency.** Each cluster's ReconciliationLoop converges independently.
There is no cross-cluster coordination or distributed transaction.

| Scenario | Behavior |
|----------|----------|
| Cluster A succeeds, Cluster B unreachable | Cluster A converges. Cluster B retries on resync interval. Application status: DEGRADED (not all clusters converged). |
| Cluster A converges, Cluster B provisioning fails permanently | Cluster A stays converged. Cluster B reports fault → `ApplicationFaultPolicy` handles. Application status: DEGRADED. |
| Rollback requested | Rollback applies to ALL clusters — `ApplicationLifecycleService.rollback()` calls `updateDesired()` on every cluster's TenantLoop with the previous deployment's graph. Per-cluster convergence remains independent. |
| Application RUNNING threshold | RUNNING requires ALL clusters converged + no active fault child cases. Any single cluster not converged → DEPLOYING or DEGRADED. |

This is the standard model for multi-cluster deployment (ArgoCD, Flux, and similar GitOps
tools all use per-cluster eventual consistency). Cross-cluster atomicity would require
distributed coordination with no clear benefit — a failed cluster should not block a
healthy one from serving traffic.

### ApplicationGoalCompiler

Compiles an `Application` into K8s infrastructure via `InfraDesiredNodeSpec` nodes:

- Implements `GoalCompiler<Application>` — returns a single `DesiredStateGraph`
- Each `ServiceDefinition` → K8s nodes (Deployment + Service + optional Ingress) wrapped in
  `InfraDesiredNodeSpec(k8sSpec, "kubernetes:" + clusterId)`
- Service dependencies → graph edges (Deployment B depends on Deployment A)
- The `backendId` format `"kubernetes:<clusterId>"` encodes cluster identity for the
  `KubernetesBackend` to resolve the correct fabric8 client

### Multi-cluster reconciliation model

`GoalCompiler<G>` returns a single `DesiredStateGraph`. The `ReconciliationLoop` is per-tenant
(keyed by `tenancyId` in `ConcurrentHashMap<String, TenantLoop>`).

Multi-cluster is handled by **one TenantLoop per cluster**, using a composite key:

1. `ApplicationGoalCompiler.compileForCluster(Application, ClusterReference, factory)` produces
   a cluster-specific graph. Services are filtered by `ServiceDefinition.targetClusters` —
   only services whose `targetClusters` is empty (deploy everywhere) or contains the current
   cluster's `clusterId` are included.
2. On deploy, the app orchestrator calls `compileForCluster()` for each target cluster and
   starts a reconciliation loop via `ReconciliationLoop.start(tenancyId + ":" + clusterId, graph)`.
3. Each `InfraDesiredNodeSpec` carries `backendId = "kubernetes:" + clusterId` — the
   `KubernetesBackend` extracts the `clusterId` and resolves the fabric8 client from
   `KubernetesClusterRegistry`.
4. On application update, `ReconciliationLoop.updateDesired(compositeKey, newGraph)` pushes
   the updated graph for each affected cluster.

This reuses the existing `ReconciliationLoop` without modification — the composite key pattern
is a deployment decision, not a runtime change.

## REST API

### Application management — `/api/applications`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/` | Create application (DRAFT status) |
| `GET` | `/` | List all applications with status |
| `GET` | `/{id}` | Full application detail |
| `PUT` | `/{id}` | Update definition (triggers reconciliation if running) |
| `DELETE` | `/{id}` | Decommission (graceful shutdown, case closure) |

### Deployment operations — `/api/applications/{id}/deployments`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/` | Deploy or redeploy |
| `GET` | `/` | Deployment history |
| `GET` | `/current` | Current state per cluster |
| `POST` | `/rollback` | Rollback to previous record |

### Service operations — `/api/applications/{id}/services/{serviceId}`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/status` | Status across all clusters |
| `POST` | `/scale` | Update replica count |
| `POST` | `/upgrade` | Upgrade image tag (triggers child case) |

### Cluster management — `/api/clusters`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/` | Register cluster |
| `GET` | `/` | List clusters with status |
| `GET` | `/{id}` | Cluster detail |
| `DELETE` | `/{id}` | Deregister (no running apps) |
| `POST` | `/{id}/test` | Test connectivity |

### Case & event visibility — `/api/applications/{id}/cases`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Application case + child cases |
| `GET` | `/{caseId}` | Case detail with blackboard, timeline |
| `GET` | `/events` | SSE stream — case and reconciliation events |

### Approvals — `/api/approvals`

Exposes the `OpsPendingApprovalHandler` (already in `casehub-ops-api`) for the UI.

Two approval layers operate at different levels:

- **Case-level approval** — `ActionRiskClassifier` gates case workers via casehub-work
  WorkItems. Used for CVE response when risk is HIGH (human approves the remediation plan
  before the worker updates desired state).
- **Provisioning-level approval** — `OpsPendingApprovalHandler` (implements
  `PendingApprovalHandler` SPI) gates desiredstate node transitions. Used for high-risk
  K8s provisioning operations (e.g., namespace deletion, production deployment).

The `/api/approvals` REST surface aggregates both:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Pending approvals across all apps (both case-level WorkItems and provisioning-level entries) |
| `GET` | `/{id}` | Approval detail with plan, risk |
| `POST` | `/{id}/approve` | Approve with optional comment |
| `POST` | `/{id}/reject` | Reject with reason |

### CVE & security — `/api/applications/{id}/security`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/cves` | Known CVEs for this app |
| `POST` | `/cves` | Ingest CVE detection (triggers child case) |
| `GET` | `/posture` | Compliance posture summary |

### Reconciliation — `/api/applications/{id}/reconciliation`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/status` | Desired vs actual, drift report per cluster |
| `POST` | `/trigger` | Force immediate reconciliation |
| `GET` | `/events` | SSE stream — reconciliation events only |

All endpoints scoped by `tenancyId` (propagated via request header, bound in JAX-RS filter).

### SSE event format

Both SSE endpoints emit **CloudEvents JSON** — the same format the `ReconciliationLoop`
already produces via `ReconciliationEventEmitter`. The SSE `event:` field carries the
CloudEvent `type`; the SSE `data:` field carries the full CloudEvent JSON envelope.

**Reconciliation events** (from desiredstate runtime — `DesiredStateEventTypes`):

| SSE event type | CloudEvent type | Data payload | When |
|---------------|----------------|-------------|------|
| `reconciliation.completed` | `io.casehub.desiredstate.reconciliation.completed` | `ReconciliationCompletedData` (tenancyId, nodeCount, provisionedCount, faultedCount) | Each reconciliation cycle completes |
| `node.faulted` | `io.casehub.desiredstate.node.faulted` | `NodeFaultedData` (nodeId, faultType, tenancyId) | Node provisioning fails |
| `node.drifted` | `io.casehub.desiredstate.node.drifted` | `NodeDriftedData` (nodeId, tenancyId) | Drift detected on a node |
| `node.recovered` | `io.casehub.desiredstate.node.recovered` | `NodeRecoveredData` (nodeId, tenancyId) | Previously faulted/drifted node recovers |

**Application-level events** (emitted by `ApplicationLifecycleService`):

| SSE event type | Data payload | When |
|---------------|-------------|------|
| `io.casehub.ops.app.child_case.spawned` | `{caseId, caseType, trigger}` | Binding fires and spawns a child case |
| `io.casehub.ops.app.child_case.completed` | `{caseId, caseType, outcome}` | Child case reaches terminal state |
| `io.casehub.ops.app.status.changed` | `{applicationId, previousStatus, newStatus}` | ApplicationStatus derivation changes |
| `io.casehub.ops.app.deployment.started` | `{applicationId, deploymentId, trigger}` | Deploy/rollback/upgrade initiated |

**Reconnection:** SSE `id:` field = CloudEvent `id` (UUID). Clients reconnect with
`Last-Event-ID` header. The server replays events from an in-memory ring buffer (configurable
size, default 1000 events per application). No persistent event store — reconnection beyond
the buffer window receives a full state snapshot as the first event.

The `/api/applications/{id}/reconciliation/events` endpoint filters to reconciliation events
only. The `/api/applications/{id}/cases/events` endpoint emits both reconciliation and
application-level events.

### Skeleton scope for REST

Every endpoint exists and returns correct response shapes. "Stubbed" means:

- **REST layer:** endpoint accepts the request, validates input, returns a well-formed response
  with realistic data (not empty or placeholder). For non-key paths, the response is
  constructed in-memory rather than driven by a real backend operation.
- **Key paths fully working end-to-end:** create application, deploy, get status, CVE ingest
  (triggers child case), approve/reject, reconciliation status and SSE events.

## Case Model

### Application Case Definition — Descriptor Pattern

The application case uses the `*CaseDescriptor` pattern recommended by PLATFORM.md for new
harnesses. `ApplicationCaseDescriptor` is a POJO that builds the `CaseDefinition` with:

- Case type: `ops:application-lifecycle`
- States, bindings, worker lambdas, capability routing, blackboard structure
- Created via `CaseDefinitionYamlMapper` from YAML + augmented with descriptor business logic

```
ApplicationCaseDescriptor
├── buildDefinition()          → CaseDefinition
├── createBindings()           → binding declarations (context triggers → child cases)
├── createWorkers()            → worker functions (CVE response, upgrade, etc.)
└── createCapabilities()       → capability routing for worker selection
```

### Application Case

One case type: `ops:application-lifecycle`. Created on first deploy, closed on decommission.

**States:** STARTING → RUNNING (WAITING) → COMPLETED (decommissioned) / FAULTED

**Blackboard:**

| Key | Content |
|-----|---------|
| `topology` | Current Application definition |
| `desiredState` | Current DesiredStateGraph reference |
| `deploymentHistory` | List of DeploymentRecords |
| `activeChildCases` | Count and summary of open child cases |
| `compliancePosture` | Latest compliance snapshot |

### Bindings (declarative child case spawning)

| Context trigger | Filter | Child case type | Input mapping |
|-----------------|--------|-----------------|---------------|
| `cveDetected` | severity CRITICAL/HIGH | `ops:cve-response` | `.cveData` |
| `upgradeRequested` | — | `ops:service-upgrade` | `.upgradeSpec` |
| `incidentDetected` | — | `ops:incident-response` | `.incidentData` |
| `scalingRequired` | — | `ops:scaling-event` | `.scalingSpec` |
| `driftDetected` | — | `ops:drift-remediation` | `.driftReport` |
| `complianceViolation` | — | `ops:compliance-remediation` | `.violationData` |

### Child case types

**Fully working:**

- **`ops:cve-response`** — receives CVE data, identifies affected services, updates desired
  state (new image in image reference), reconciliation drives rollout, closes on convergence.
  Case-level approval gate via `ActionRiskClassifier` when risk is HIGH — creates a WorkItem
  for human approval before the worker modifies desired state.
- **`ops:service-upgrade`** — receives target service + new image reference, updates desired
  state, reconciliation drives rollout, closes on convergence.

**Wired but stubbed** (tracked as issues under epic #29):

- `ops:incident-response` (#34)
- `ops:scaling-event` (#35)
- `ops:drift-remediation` (#36)
- `ops:compliance-remediation` (#37)

All have correct models, bindings fire, child cases spawn, workers are no-ops.

### Signal flow

External → REST API → `CaseHubRuntime.signal(appCaseId, path, data)` → context change →
binding evaluates → child case spawns.

## Kubernetes Integration

### KubernetesBackend (in app/ module)

`InfraBackend` SPI implementation using fabric8 kubernetes-client. Lives in
`app/src/main/java/io/casehub/ops/app/k8s/`:

| Class | Purpose |
|-------|---------|
| `KubernetesBackend` | `InfraBackend` SPI implementation — `backendId()` returns `"kubernetes"`, extracts `clusterId` from composite `backendId` format `"kubernetes:<clusterId>"` |
| `KubernetesClusterRegistry` | Manages fabric8 client instances per cluster |
| `KubernetesActualStateAdapter` | `ActualStateAdapter` SPI implementation — reads actual K8s state via fabric8, returns `NodeStatus` per node |
| `resource/DeploymentProvisioner` | K8s Deployment CRUD |
| `resource/ServiceProvisioner` | K8s Service CRUD |
| `resource/IngressProvisioner` | K8s Ingress CRUD |
| `resource/NamespaceProvisioner` | K8s Namespace CRUD |
| `resource/ConfigMapProvisioner` | K8s ConfigMap CRUD |

### SPI implementations in app/

| SPI | Implementation | Notes |
|-----|---------------|-------|
| `GoalCompiler<Application>` | `ApplicationGoalCompiler` | Application → InfraDesiredNodeSpec graph |
| `ActualStateAdapter` | `KubernetesActualStateAdapter` | Reads K8s state via fabric8, returns NodeStatus |
| `NodeProvisioner` | `ApplicationNodeProvisioner` | Unwraps InfraDesiredNodeSpec, dispatches to KubernetesBackend |
| `FaultPolicy` | `ApplicationFaultPolicy` | Default fault rules for K8s provisioning failures |
| `EventSource` | `KubernetesEventSource` | Passive Multi<StateEvent> with emit() for K8s watch events |

### Mapping to existing infra specs

The infra api already defines `K8sDeploymentSpec`, `K8sServiceSpec`, `K8sIngressSpec`,
`K8sNamespaceSpec`. `K8sConfigMapSpec` is added (#39). `KubernetesBackend` translates these
into fabric8 API calls. Each resource provisioner handles one K8s resource type.

### Drift detection — reuses desiredstate runtime

Drift detection follows the standard desiredstate architecture:

1. `KubernetesActualStateAdapter` (implements `ActualStateAdapter`) reads live K8s resources
   via fabric8 and returns `NodeStatus` per node (PRESENT, ABSENT, DRIFTED, UNKNOWN)
2. The `ReconciliationLoop` compares desired state against actual state
3. DRIFTED nodes get re-provisioned (TransitionPlanner treats DRIFTED as needing provision,
   per casehub-desiredstate#38)

The adapter detects K8s-specific drift by comparing:
- Replica count mismatch
- Image reference mismatch
- Resource limit changes
- Missing resources (deleted externally)
- Extra resources (created outside the system)

There is no separate `KubernetesDriftDetector` component — drift detection is the runtime's
job. The adapter's responsibility is reading actual state accurately.

### Multi-cluster

`KubernetesClusterRegistry` holds a `KubernetesClient` per registered cluster. Clients are
pooled and reused. Each cluster gets its own TenantLoop in the ReconciliationLoop (see
§Multi-cluster reconciliation model above).

### Testing

Fabric8 `KubernetesMockServer`:

- Used in integration tests and demo mode
- Pre-loaded with cluster state simulating a real environment
- Can inject failures (pod crash, node drain) to trigger fault policies
- Same client API — no conditional logic in production code

## UI Design

### Tech stack

Lit 3.x Web Components following blocks-ui conventions — OKLCH design tokens, Shadow DOM,
container queries, `pages-event` protocol, dual data mode. New components published as
`@casehubio/blocks-ui-ops-*` packages.

### Application shell

casehub-pages layout:

- **Left sidebar:** Applications, Clusters, Approvals, Settings
- **Top bar:** tenancy selector, notifications badge (pending approvals, active incidents)
- **Main area:** routed content

### Screens

**Screen 1: Applications list**
- Table with status badges (RUNNING, DEGRADED, DEPLOYING)
- Per-app summary: service count, cluster count, open child cases
- Actions: New Application (wizard), click to open dashboard

**Screen 2: Deployment wizard** (form-driven, 5 steps)
1. Application basics — name, description
2. Services — add/remove, each with: name, image (including tag), replicas, ports, env, resources, dependencies
3. Clusters — select from registered, assign namespace per cluster, assign services to clusters (default: all services to all clusters)
4. Policies — compliance policies, approval thresholds
5. Review & deploy — topology graph preview, YAML export, deploy button

**Screen 3: Application dashboard**
- **Topology panel** (`ops-topology-view`) — dependency graph, colour-coded by health
- **Service cards** (`ops-service-card`) — selected service detail: image, replicas, health, per-cluster status
- **Event timeline** (`ops-event-timeline`) — chronological lifecycle events
- **Reconciliation status** — desired vs actual diff per cluster, timestamps
- **Active cases panel** — open child cases with status

**Screen 4: Case detail**
- Reuses `work-item-detail` pattern — tabs for activity, relations
- Blackboard summary, timeline, outcome
- Action bar (approve, escalate, close)

**Screen 5: Cluster management**
- List with connectivity status
- Register new cluster form
- Per-cluster: hosted applications, resource summary

**Screen 6: Approvals**
- `ops-approval-panel` — pending approvals across all apps
- Risk classification, plan detail
- Approve/reject with comment

### Skeleton scope for UI (#38)

All screens exist and navigate. Wizard completes end-to-end. Dashboard shows live SSE data.
Case detail works for CVE and upgrade. Other screens render with realistic stubbed data.

## Demo Environment

### Kind (Kubernetes in Docker)

Kind is Kubernetes IN Docker. Docker is the officially supported container runtime. Podman
can be used as an alternative via `KIND_EXPERIMENTAL_PROVIDER=podman` but is not the default.

Three runtime profiles:

**`demo` profile** (default for quickstart):
- Starts two Kind clusters (`ops-prod`, `ops-staging`)
- Pre-registers both in ClusterRegistry on startup
- Loads `online-store.yaml` sample topology
- Ready to deploy from wizard immediately

**`mock` profile** (fallback / CI / conference):
- Fabric8 KubernetesMockServer, no real containers
- Pre-loaded with realistic cluster state
- All UI and case flows work identically
- Zero external dependencies

**`production` profile**:
- Connects to real clusters via registered credentials
- No auto-setup, no sample data

### Kind setup

Managed by startup script in `examples/`:

- Creates Kind clusters using Docker as container runtime (Podman supported via
  `KIND_EXPERIMENTAL_PROVIDER=podman`)
- Configures kubeconfig for fabric8 client access
- Tears down on app shutdown (configurable)
- Two clusters: prod gets full topology, staging gets gateway only (via `ServiceDefinition.targetClusters` — gateway targets both clusters, orders and inventory target `ops-prod` only)

## Demo Scenario

### Sample topology — `examples/quarkus-microservices/online-store.yaml`

Three Quarkus services:

- **gateway** — REST gateway, depends on orders + inventory, 2 replicas, ingress
- **orders** — order service, depends on inventory, 2 replicas
- **inventory** — inventory service, no dependencies, 2 replicas

Uses public Quarkus quickstart images.

### Scenarios

**Scenario 1: Initial deployment** (fully working)
- Load topology through wizard → deploy → watch services go green in dependency order

**Scenario 2: CVE response** (fully working)
- POST CVE against inventory's base image → child case spawns → desired state updates →
  rolling update → child case closes on convergence

**Scenario 3: Service upgrade** (fully working)
- Upgrade orders to new version → child case → rolling update → close

**Scenario 4: Drift detection** (working)
- External kubectl change → reconciliation detects drift → auto-remediation

**Scenarios 5–8** (stubbed — events fire, child cases spawn, workers no-op):
- Compliance violation (#37), scaling event (#35), incident response (#34), decommission

## Implementation Phases

### Phase 1: Foundation

App module, models, persistence, REST endpoints, engine bootstrap.

- Maven module with dependencies (engine, engine-work-adapter, desiredstate, ops-api, fabric8)
- JPA entities for Application, ClusterReference, DeploymentRecord
- Flyway migrations (`db/app/migration/V1__application.sql`, `V2__cluster.sql`, `V3__deployment_record.sql`)
- Named datasource configuration (`quarkus.datasource.app.*`) — H2 for demo/mock, PostgreSQL for production
- ApplicationLifecycleService (deploy, update, rollback, decommission, status)
- All REST endpoints returning stubbed responses
- Engine embedded with ApplicationCaseDescriptor wired
- ApplicationGoalCompiler
- Build passes, app starts

### Phase 2: Kubernetes integration

KubernetesBackend and SPI implementations in app module.

- KubernetesBackend implementing InfraBackend SPI
- Resource provisioners (Deployment, Service, Ingress, Namespace, ConfigMap)
- KubernetesActualStateAdapter implementing ActualStateAdapter
- KubernetesClusterRegistry for multi-cluster
- Startup recovery — `@Startup` reconciliation loop re-initialization for active applications
- ApplicationNodeProvisioner, ApplicationFaultPolicy, KubernetesEventSource
- Integration tests with fabric8 mock server
- Deploy flow end-to-end: REST → compile → reconcile → real K8s resources

### Phase 3: Case lifecycle

Child cases and event-driven response.

- Application case bindings for all event types
- CVE response fully working (with ActionRiskClassifier approval gate)
- Service upgrade fully working
- Remaining child cases wired but stubbed (#34, #35, #36, #37)
- OpsPendingApprovalHandler integrated for provisioning-level approval

### Phase 4: UI

blocks-ui components and pages application.

- New blocks-ui components
- Pages shell with routing (standalone, not iframe)
- Wizard end-to-end
- Dashboard with live SSE
- Case detail and approval screens
- Cluster management

### Phase 5: Demo & polish

Sample topology, scenarios, documentation.

- online-store.yaml
- Scenario scripts
- Demo mode (auto Kind setup, sample data)
- README walkthrough

Each phase is independently demoable.
