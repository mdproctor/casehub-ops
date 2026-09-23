# casehub-ops -- Consumer Guide

> Domain implementations of casehub-desiredstate SPIs for CaseHub deployment, infrastructure, compliance, and IoT concerns.

**GitHub:** [casehubio/casehub-ops](https://github.com/casehubio/casehub-ops)
**Tier:** Integration (Research project + reference architecture)

---

## Purpose

casehub-ops bridges the generic desired-state runtime (`casehub-desiredstate`) to concrete CaseHub operational domains. `casehub-desiredstate` stays domain-agnostic; `casehub-ops` is the CaseHub domain layer above it.

Each domain module implements the full desiredstate SPI quad -- `GoalCompiler`, `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, `EventSource` -- for one operational concern. The `app` module combines them into a Quarkus application with Kubernetes lifecycle management.

---

## Module Structure

| Module | Artifact | Purpose |
|--------|----------|---------|
| `api` | `casehub-ops-api` | Shared types: sealed `NodeSpec` hierarchies, SPIs (`EvidenceCollector`, `InfraBackend`, `ResourceProvisioner`, `ApprovalEvaluator`, `ApprovalAuthorizer`, `PlanStore`, `NodeDriftChecker`), goal records, and approval types used across all domain modules |
| `deployment` | `casehub-ops-deployment` | CaseHub agent topology desired state: agents, channels, case types, trust policies, endpoints. Includes adaptive topology (RAS-driven recompilation) and per-node-type drift checkers |
| `infra` | `casehub-ops-infra` | Infrastructure provisioning: Terraform/Ansible augmentation PoC with standalone backend. Three operating modes: standalone, Terraform, Ansible |
| `compliance` | `casehub-ops-compliance` | Regulatory compliance posture: SOC2-TypeII, GDPR, EU-AI-Act Art.12, DORA, NIS2, ISO27001. Strategy-based evidence collection with tamper-evident ledger records |
| `iot` | `casehub-ops-iot` | IoT device desired state: physical device installation (human-gated), device configuration via `DeviceProvider` (Home Assistant/OpenHAB). Capability normalization and command mapping |
| `app` | `casehub-ops-app` | Ops console: Quarkus application for K8s microservice lifecycle management. Embeds casehub-engine and casehub-desiredstate runtime. REST API, JPA entities, Flyway migrations, fabric8 K8s integration |
| `testing` | `casehub-ops-testing` | Shared test fixtures: dependency aggregator POM for `casehub-ops-api`, `casehub-desiredstate-testing`, `casehub-platform-testing`. No Java sources |

---

## API Module -- Shared Types

The `api` module is a pure-Java library with no runtime dependencies. It defines the types that all domain modules consume.

### Deployment Node Specs

`DeploymentNodeSpec` -- sealed interface extending `NodeSpec`. Six permits:

| Class | Node type | Key fields |
|-------|-----------|------------|
| `AgentNodeSpec` | `agent` | `agentId`, `name`, `slot`, `provider`, `modelFamily`, `modelVersion`, `capabilities`, `disposition`, `providerConfigs`. `toDescriptor(tenancyId)` converts to `AgentDescriptor`. `withAgentId(newId)` for adaptive topology cloning |
| `ChannelNodeSpec` | `channel` | channel configuration for qhorus |
| `CaseTypeNodeSpec` | `case_type` | `namespace`, `name`, `version`, `title`, `summary`, `definitionFile`, `definitionPayload` |
| `TrustPolicyNodeSpec` | `trust_policy` | trust routing policy configuration |
| `EndpointNodeSpec` | `endpoint` | REST/service endpoint configuration |
| `DetectionNodeSpec` | `detection` | `situationId`, `eventTypes`, `correlationWindow`, `chainMode`, `triggerAction`. `toRegistration()` converts to `SituationRegistration` for RAS |

All implement `nodeId()` and `nodeType()`.

### Infrastructure Node Specs

`InfraNodeSpec` -- sealed interface. Ten permits covering K8s resources, cloud infrastructure, and IaC tools:

| Class | Resource type |
|-------|---------------|
| `K8sNamespaceSpec` | `k8s_namespace` |
| `K8sDeploymentSpec` | `k8s_deployment` -- includes `namespace`, `name`, `image`, `replicas`, `resources`, `labels`, `ports`, `env`, `healthCheck` |
| `K8sServiceSpec` | `k8s_service` -- includes `ServiceType` (CLUSTER_IP, NODE_PORT, LOAD_BALANCER) |
| `K8sIngressSpec` | `k8s_ingress` |
| `K8sConfigMapSpec` | `k8s_configmap` |
| `ComputeInstanceSpec` | `compute_instance` -- VM provisioning with `InstanceType`, `NetworkConfig` |
| `DatabaseClusterSpec` | `database_cluster` -- managed DB with `DatabaseEngine`, `ClusterSize`, `BackupConfig` |
| `TerraformWorkspaceSpec` | `terraform_workspace` -- Terraform state management |
| `AnsiblePlaybookSpec` | `ansible_playbook` -- Ansible execution spec |
| `GenericResourceSpec` | `generic_resource` |

### IoT Node Specs

`IoTNodeSpec` -- sealed interface extending `NodeSpec`. Two permits:

| Class | Fields |
|-------|--------|
| `PhysicalDeviceSpec` | `deviceId`, `deviceClass` (from casehub-iot-api), `label` |
| `DeviceConfigSpec` | `deviceId`, `deviceClass`, `desiredCapabilities` (Map) |

### Approval SPIs

The approval subsystem spans all domain modules:

| Interface | Purpose |
|-----------|---------|
| `ApprovalEvaluator` | `evaluate(DesiredNode, StepAction, tenancyId)` -> `ApprovalDecision`. Decides whether a node operation requires human approval |
| `ApprovalAuthorizer` | `authorize(RiskClassification, actorId, actorRoles)` -> `AuthorizationResult` (sealed: `Authorized` or `Denied`). Role-based gating |
| `PlanStore` | `store(ApprovalPlan)` / `retrieve(planReference)` / `remove(planReference)`. Stores approval plans between creation and execution |
| `ApprovalThresholds` | Risk classification configuration with namespace-aware thresholds |

`RiskClassification` enum: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.

### Compliance SPIs

| Interface | Purpose |
|-----------|---------|
| `EvidenceCollector` | `strategy()` returns strategy key; `collect(ComplianceControlSpec, tenancyId)` returns `EvidenceResult` |
| `ComplianceControlSpec` | Record: `controlId`, `controlType`, `strategy`, `title`, `frameworks` (list of `FrameworkMapping`), `evidenceMaxAgeDays`, `requiresHumanReview`, `properties` map |

### Infrastructure SPIs

| Interface | Purpose |
|-----------|---------|
| `InfraBackend` | `provision`/`deprovision`/`readState`/`detectDrift`/`plan`. Backend-specific operations for infrastructure nodes |
| `ResourceProvisioner` | `provisionerId()` / `handles(InfraNodeSpec)` / `execute(ProvisionTask)`. Higher-level provisioner routing |

### Drift Detection

`NodeDriftChecker` -- per-node-type drift checking: `check(NodeSpec, tenancyId)` returns `NodeStatus`, `nodeType()` for routing.

---

## Deployment Module

### Goal Compilation

`DeploymentGoalCompiler` implements `GoalCompiler<DeploymentGoals>`. Compiles five entry types (agents, channels, case types, trust policies, endpoints) into a `DesiredStateGraph`. Case type entries with a `definitionFile` have their payload resolved via `DefinitionPayloadLoader`.

`DeploymentGoals` record aggregates:
- `agents()` -- `List<GoalEntry<AgentNodeSpec>>`
- `channels()` -- `List<GoalEntry<ChannelNodeSpec>>`
- `caseTypes()` -- `List<GoalEntry<CaseTypeNodeSpec>>`
- `trust()` -- `List<GoalEntry<TrustPolicyNodeSpec>>`
- `endpoints()` -- `List<GoalEntry<EndpointNodeSpec>>`
- `adaptations()` -- `List<AdaptationRuleSpec>` (for adaptive topology)

### Drift Checkers

Five `NodeDriftChecker` implementations -- one per deployment node type:

| Class | Node type | Mechanism |
|-------|-----------|-----------|
| `AgentDriftChecker` | `agent` | Delegates to `AgentDescriptorComparator.compare()` for field-by-field comparison via `AgentRegistry.findById()` |
| `ChannelDriftChecker` | `channel` | Full field comparison including connector binding and tenancy |
| `CaseTypeDriftChecker` | `case_type` | Case type definition comparison |
| `EndpointDriftChecker` | `endpoint` | Endpoint configuration comparison |
| `TrustPolicyDriftChecker` | `trust_policy` | Trust policy comparison |

### Provision Handlers

Five handlers -- one per node type: `AgentProvisionHandler`, `ChannelProvisionHandler`, `CaseTypeProvisionHandler`, `EndpointProvisionHandler`, `TrustPolicyProvisionHandler`.

### Fault Policy

`DeploymentFaultPolicy` -- delegates to `ThresholdFaultPolicy`. Monitors `PROVISION_FAILED` faults across all 5 node types. After 3 consecutive failures, adds a `deployment-review` node (human escalation) using `DeploymentReviewSpec`.

### Adaptive Topology (RAS-Driven)

`DeploymentAdaptiveSituationRecompiler` -- situation-driven topology recompilation. Connects RAS (`casehub-ras`) situations to deployment topology:

- Observes `SituationChangeEvent` via `@ObservesAsync` for immediate recompilation
- Periodic 5-minute safety-net poll for missed CDI events
- Per-tenant state tracking via `TenantAdaptationState` with hysteresis (`shouldActivate()`/`clearAbsentSituations()`)
- Three action types: `ScaleAction`, `AddAction`, `UpdateAction`
- Graph content equality comparison (not version-based)
- `ReconciliationTarget` interface abstracts the reconciliation loop

### Approval Evaluator

`DeploymentApprovalEvaluator` implements `ApprovalEvaluator` -- risk-gated approval for deployment operations.

### Supporting Classes

- `DeploymentGoalLoader` -- YAML loading for deployment goals
- `DeploymentActualStateAdapter` -- actual state reading via drift checkers
- `DeploymentEventSource` -- event source for deployment domain
- `DeploymentNodeProvisioner` -- dispatches to provision handlers
- `DeploymentProviderConfigStore` -- provider configuration with reverse index for `declaredAgentIds()`
- `DeploymentProvisionerConfigRegistry` -- CDI-backed provisioner config registry
- `DeploymentTrustRoutingPolicyProvider` -- trust routing integration with `id()` method
- `SpecHashStore` -- tracks spec hashes for change detection

---

## Compliance Module

### Goal Compilation

`ComplianceGoalCompiler` implements `GoalCompiler<ComplianceGoals>`. Each `ComplianceControlSpec` becomes a `DesiredNode`. Controls with `requiresHumanReview=true` get `HumanGating.ALL`.

### Framework Registry

`ComplianceFrameworkRegistry` -- in-memory registry mapping framework identifiers (SOC2, GDPR, etc.) to controls. Supports `controlsForFramework()`, `frameworksForControl()`, `registeredFrameworks()`.

### Evidence Collection

`ComplianceEvidenceService` -- routes evidence collection to strategy-based `EvidenceCollector` implementations:

| Collector | Strategy | What it checks |
|-----------|----------|----------------|
| `FileExistenceEvidenceCollector` | `file-existence` | Verifies files exist (or are absent) at expected paths |
| `CertificateExpiryEvidenceCollector` | `certificate-expiry` | Checks TLS/SSL certificate expiry dates |
| `ConfigHashEvidenceCollector` | `config-hash` | Verifies configuration file SHA-256 hashes match approved baselines |
| `LogDirectoryEvidenceCollector` | `log-directory` | Validates log directory existence, permissions, and retention policies |

### Posture Service

`CompliancePostureService` -- computes framework posture by aggregating evidence status across controls. Returns `FrameworkPosture` with `ControlStatus` per control.

### Ledger Integration

`ComplianceLedgerEntry` -- extends `JpaLedgerEntry` for tamper-evident compliance records via `casehub-ledger`.

### Full SPI Quad

| Class | Role |
|-------|------|
| `ComplianceGoalCompiler` | Compiles controls to graph |
| `ComplianceActualStateAdapter` | Reads actual compliance state |
| `ComplianceNodeProvisioner` | Provisions compliance checks |
| `ComplianceFaultPolicy` | No-op (compliance faults are not retried) |
| `ComplianceEventSource` | Event source for compliance domain |
| `ComplianceApprovalEvaluator` | Risk-gated approval for compliance operations |

---

## Infrastructure Module

### Operating Modes

Three modes via `InfraNodeSpec` sealed hierarchy:

1. **Standalone** (`StandaloneNodeSpec` implied by K8s specs) -- native provisioning without external IaC tools
2. **Terraform augmentation** (`TerraformWorkspaceSpec`) -- wraps Terraform with audit trail, reconciliation, and human governance
3. **Ansible augmentation** (`AnsiblePlaybookSpec`) -- wraps Ansible with the same capabilities

### InfraBackend SPI

`InfraBackend` -- blocking interface (migrated from Uni per ADR-0005):
- `provision(InfraNodeSpec, InfraProvisionContext)` -> `BackendProvisionResult`
- `deprovision(InfraNodeSpec, InfraProvisionContext)` -> `BackendDeprovisionResult`
- `readState(NodeId, InfraNodeSpec)` -> `ResourceState`
- `detectDrift(NodeId, InfraNodeSpec)` -> `DriftReport`
- `plan(InfraNodeSpec, InfraProvisionContext)` -> `Optional<ProvisionPlan>`

Implementation: `StandaloneBackend` (in-memory, test-friendly). `TerraformBackend` and `AnsibleBackend` are planned.

### Full SPI Quad

| Class | Role |
|-------|------|
| `InfraGoalCompiler` | Translates goal specs into `InfraNodeSpec` hierarchy |
| `InfraActualStateAdapter` | Reads actual infrastructure state via `InfraBackend.readState()` |
| `InfraNodeProvisioner` | Dispatches to `ResourceProvisioner` implementations |
| `InfraFaultPolicy` | Delegates to `ThresholdFaultPolicy`. Monitors 8 node types. Threshold: 3. Escalates to `infra-review` node |
| `InfraEventSource` | Event source for infrastructure domain |
| `InfraApprovalEvaluator` | Risk-gated approval for infrastructure operations |

### Resource Provisioner SPI

`ResourceProvisioner` -- `provisionerId()` / `handles(InfraNodeSpec)` / `execute(ProvisionTask)`. Implementation: `InMemoryResourceProvisioner` (standalone, test-friendly).

---

## IoT Module

### Goal Compilation

`IoTGoalCompiler` implements `GoalCompiler<IoTGoals>`. Two node types:

- **Physical devices** (`physical-device`) -- human-gated (`HumanGating.ALL`). Physical installation required. Generates a companion `device-config` node with a dependency edge
- **Device configurations** (`device-config`) -- automated provisioning via `DeviceProvider`

Duplicate `deviceId` values are rejected.

### Capability Normalization

`CapabilityNormalizer` -- normalizes capability values for comparison:
- BigDecimal normalization (strips trailing zeros)
- Enum normalization (uppercase)
- Temperature normalization
- Recursive map normalization

### Command Mapping

`CapabilityCommandMapper` -- converts capability key+desired value to `DeviceCommand` with `CommandContext` (dispatched-by, correlation ID).

### Provisioner

`IoTNodeProvisioner` implements `NodeProvisioner`:
- `handledTypes()`: `physical-device`, `device-config`, `iot-review`
- `resyncInterval()`: 30 seconds
- Provision flow: evaluates approval -> if approved, routes to `DeviceProvider` via `DeviceRegistry`
- Full approval lifecycle with plan/apply pattern and stale-plan detection

### Full SPI Quad

| Class | Role |
|-------|------|
| `IoTGoalCompiler` | Compiles device goals to graph (2 node types) |
| `IoTActualStateAdapter` | Reads actual device state via `DeviceRegistry` with normalized comparison |
| `IoTNodeProvisioner` | Sealed dispatch to `DeviceProvider`. Full approval lifecycle |
| `IoTFaultPolicy` | Delegates to `ThresholdFaultPolicy`. Monitors `device-config`. Threshold: 3. Escalates to `iot-review` node |
| `IoTEventSource` | Bridges `StateChangeEvent`/`ProviderStatusEvent` CDI events |
| `IoTApprovalEvaluator` | Risk-gated approval for IoT operations |

### Goal Loading

`IoTGoalLoader` -- YAML loading with classpath-first strategy, directory merge, and duplicate device rejection.

---

## App Module -- Ops Console

### Purpose

Quarkus application for K8s microservice lifecycle management. Embeds `casehub-engine` and `casehub-desiredstate` runtime. This is the operational console -- it does not delegate to domain modules; it implements the SPI quad directly for K8s resource management.

### REST API

| Resource | Path | Operations |
|----------|------|------------|
| `ApplicationResource` | `/api/applications` | CRUD: POST (create draft), GET (list/get), PUT (update), DELETE (decommission) |
| `DeploymentResource` | `/api/applications/{id}/deploy` | POST -- triggers deployment to registered clusters |
| `ClusterResource` | `/api/clusters` | CRUD for K8s cluster references |
| `ScalingResource` | `/api/applications/{appId}/services/{serviceId}/scale` | POST -- manual or RAS-driven scaling with cooldown enforcement |
| `ApprovalResource` | `/api/approvals` | GET (list/get), POST approve/reject with role-based authorization |
| `OpsReconciliationApi` | `/api/ops/reconciliation` | Status, trigger, `@PlatformStream` events |
| `SecurityResource` | Security endpoints | Security operations |
| `CaseResource` | Case management | Case operations |
| `ServiceOperationResource` | Service operations | Service-level operations |

Tenancy: all requests filtered by `TenancyFilter` using `X-Tenancy-Id` header.

### Application Lifecycle

`ApplicationLifecycleService` -- central orchestrator:

1. `createDraft()` -- creates application entity in DRAFT status
2. `deploy()` -- compiles ServiceDefinitions -> K8s DesiredStateGraph per cluster, starts reconciliation loops, registers scaling evaluators and drift signal bridges
3. `decommission()` -- pushes empty graph, registers decommission handler
4. `updateServiceReplicas()` -- updates replica count, recompiles graph per cluster

Status progression: DRAFT -> DEPLOYING -> RUNNING/DEGRADED -> DECOMMISSIONING -> DECOMMISSIONED.

### Kubernetes Integration

**K8sClientRegistry** -- multi-cluster client management:
- `register(clusterId, apiUrl, credentialRef, trustCerts)` -- creates fabric8 `KubernetesClient`
- `withRetryOn401()` -- reactive credential refresh on 401 responses
- `checkExpiring()` -- `@Scheduled(every="60s")` proactive refresh when < 5 minutes to expiry
- `refreshClient()` -- `CompletableFuture` coalescing deduplicates concurrent refreshes
- Fires `CredentialRefreshedEvent` CDI event on refresh
- `CredentialResolver` SPI for pluggable credential sources (bearer token, user/password, API key)

**K8sResourceHandler** implementations:
- `K8sNamespaceHandler` -- create/update/delete K8s namespaces
- `K8sDeploymentHandler` -- create/update/delete K8s deployments
- `K8sServiceHandler` -- create/update/delete K8s services
- `K8sIngressHandler` -- create/update/delete K8s ingresses
- `K8sConfigMapHandler` -- create/update/delete K8s config maps

`K8sHandlerRegistry` -- routes `InfraNodeSpec` to the correct `K8sResourceHandler`.

**K8sWatchManager** -- real-time drift detection:
- fabric8 `Watch` on 4 resource types: deployments, services, configmaps, ingresses
- Filtered by `managed-by=casehub-ops` label
- Emits `StateEvent` with `NodeStatus.DRIFTED` or `ABSENT` on MODIFIED/DELETED
- Auto-reconnects watches on `CredentialRefreshedEvent`

### Goal Compilation

`ApplicationGoalCompiler` -- compiles `ServiceDefinition` list into K8s `DesiredStateGraph` per cluster:
- Creates namespace node, deployment nodes, service nodes
- Inter-service dependencies via `dependsOn`
- `targetClusters` filtering for multi-cluster targeting
- Labels: `managed-by=casehub-ops`, `app=<serviceId>`

### Case Model

Two child case descriptors for automated operational responses:

**DriftRemediationCaseDescriptor** (`drift-remediation`):
- 3 workers: classify -> remediate -> escalate
- Classification: persistent (consecutive count > 1), multi-node, security-sensitive fields (image, serviceAccount, rbac, secrets)
- Benign drift: auto-remediates via `NodeConvergenceTracker`
- Critical drift: escalates with risk=HIGH

**ScalingEventCaseDescriptor** (`scaling-event`):
- 3 workers: evaluate -> execute -> verify-convergence
- `ScalingPolicy` clamping: `minReplicas`, `maxReplicas`, cooldown duration
- Delegates to `ApplicationLifecycleService.updateServiceReplicas()`
- Tracks convergence via `NodeConvergenceTracker`

### Scaling

- **REST-triggered**: `ScalingResource` POST with `ScaleServiceRequest`
- **RAS situation-driven**: `SituationScalingEvaluator` observes situation changes and fires `ScalingRequestedEvent`
- **Cooldown enforcement**: per-application/service timestamp tracking
- **Scaling rules**: defined per `ServiceDefinition` with min/max replicas and cooldown period

### Approval Workflow

`K8sApprovalEvaluator` -- namespace-aware risk classification for K8s operations.
`ConfigDrivenApprovalAuthorizer` -- role-based authorization for approval actions.
`JpaPlanStore` -- JPA-backed persistent approval plan storage.

### Recovery and Lifecycle Services

- `StartupRecoveryService` -- `@Observes StartupEvent` restarts reconciliation loops for non-terminal applications
- `DecommissionCompletionHandler` -- stops loops and cleans up indexes on decommission convergence
- `DeploymentOutcomeTracker` -- CDI CloudEvent observer for async deploy convergence tracking
- `DriftSignalBridge` -- bridges drift detection events to the case model
- `ScalingSignalBridge` -- bridges scaling events to the case model
- `NodeConvergenceTracker` -- tracks per-node convergence for case completion
- `ClusterService.delete()` -- rejects with 409 when active loops reference the cluster

### RAS Health Monitoring

37 ganglia and 15 situations wired for operational health detection via CDI observer. `SituationChangeEvent` bridges RAS detections to the ops case lifecycle.

### Case Descriptors

- `ServiceUpgradeCaseDescriptor` — four-phase service upgrade lifecycle (plan, pre-check, execute, verify)
- `CveResponseCaseDescriptor` — four-phase CVE remediation lifecycle
- `ComplianceRemediationCaseDescriptor` — child case for compliance-driven remediation

### CVE Persistence

`CveStore` interface + `JpaCveStore` implementation (Flyway V6). `CveStatusObserver` tracks CVE status transitions.

### Application Event Broadcasting

`ApplicationEventBroadcaster` — SSE with ring buffer and gap detection for UI clients.

### Service Lifecycle Extensions

- `updateServiceImage(serviceId, imageRef)` — update container image
- `rollbackToDeployment(serviceId, deploymentId)` — rollback to specific deployment

---

## Dependencies

### api module

| Artifact | Nature |
|----------|--------|
| `casehub-desiredstate-api` | `NodeSpec`, `GoalCompiler`, `NodeProvisioner`, `ActualStateAdapter`, `FaultPolicy`, `EventSource` SPIs |
| `casehub-eidos-api` | `AgentDescriptor`, `AgentCapability`, `AgentDisposition`, `DispositionAxis` |
| `casehub-qhorus-api` | Channel types |
| `casehub-platform-api` | `Path`, `Preferences`, `CurrentPrincipal`, `CredentialResolver` |
| `casehub-iot-api` | `DeviceClass`, `DeviceProvider`, `DeviceRegistry` types |

### deployment module

| Artifact | Nature |
|----------|--------|
| `casehub-ops-api` | Shared types |
| `casehub-desiredstate-api` | SPI interfaces |
| `casehub-ras-api` | `SituationChangeEvent`, `SituationSource`, `ActiveSituation` |
| `casehub-eidos-api` | Agent registry for drift checking |
| `casehub-ledger-api` | Ledger integration |
| `casehub-qhorus-api` | Channel management |
| `casehub-work-api` | WorkItem for human gates |
| `casehub-engine-api` | Engine integration |
| `casehub-platform-api` | Platform services |

### compliance module

| Artifact | Nature |
|----------|--------|
| `casehub-ops-api` | Shared types |
| `casehub-desiredstate-api` | SPI interfaces |
| `casehub-platform-api` | Platform services |
| `casehub-work-api` | WorkItem for human-gated controls |
| `casehub-ledger-api` + `casehub-ledger` | Tamper-evident compliance records |

### infra module

| Artifact | Nature |
|----------|--------|
| `casehub-ops-api` | Shared types |
| `casehub-desiredstate-api` | SPI interfaces |
| `casehub-platform-api` | Platform services |
| `casehub-work-api` | WorkItem for human nodes |

### iot module

| Artifact | Nature |
|----------|--------|
| `casehub-ops-api` | Shared types |
| `casehub-desiredstate-api` | SPI interfaces |
| `casehub-platform-api` | Platform services |
| `casehub-work-api` | WorkItem for physical device installation |
| `casehub-iot-api` | `DeviceProvider`, `DeviceRegistry`, `DeviceCommand`, `CommandResult` |

### app module

| Artifact | Nature |
|----------|--------|
| `casehub-ops-api` | Shared types |
| `casehub-engine` | Case engine runtime |
| `casehub-desiredstate` | Desired-state runtime (`ReconciliationLoop`) |
| `casehub-desiredstate-work` | WorkItem integration for desiredstate |
| `casehub-ras-api` | Situation-driven scaling |
| `casehub-work` | WorkItem runtime |
| `casehub-platform` | Platform runtime |
| `casehub-platform-expression` | Expression evaluation |
| `io.fabric8:kubernetes-client` | Kubernetes API client |
| Quarkus: `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-hibernate-orm-panache`, `quarkus-flyway`, `quarkus-jdbc-h2`, `quarkus-jdbc-postgresql`, `quarkus-hibernate-validator` |
