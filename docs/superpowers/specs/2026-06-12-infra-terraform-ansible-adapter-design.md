# Infra Module — Terraform/Ansible Adapter Design

**Date:** 2026-06-12
**Issue:** casehubio/casehub-ops#1
**Status:** Design (revision 7)
**Branch:** issue-1-infra-terraform-ansible-design

---

## 1. Problem Statement

Infrastructure provisioning today operates in two paradigms:

- **Procedural** (Ansible) — describes how to reach a state. Order-dependent, no continuous reconciliation, no persistent state model.
- **Declarative point-in-time** (Terraform) — describes what should exist. Plans and applies on demand. Drift goes undetected between manual runs. No first-class human-in-the-loop, no fault recovery model, no tamper-evident audit trail.

Neither captures the full lifecycle: declare intent → plan transition → execute → reconcile continuously → recover from fault → govern with human oversight and trust.

The casehub-ops infra module implements the infrastructure provisioning domain on top of the casehub-desiredstate generic runtime. It serves three purposes:

1. **Proof-of-concept** — validates that the generic runtime's SPI contracts work for a universally understood domain.
2. **Research** — explores what CaseHub's desired-state model can do for infrastructure management, standalone and compared to existing tools.
3. **Enterprise integration** — demonstrates augmenting Terraform/Ansible with governance, continuous reconciliation, and trust-weighted execution. IBM/Red Hat angle: Ansible with tamper-evident audit, continuous reconciliation, and human governance gates.

---

## 2. Design Principles

### 2.1 Concern Separation at the Right Boundary

Three layers, each with a clean responsibility:

| Layer | Responsibility | Knows about |
|-------|---------------|-------------|
| **Layer 1: desiredstate runtime** | Graphs, nodes, edges, planners, reconciliation loops, fault primitives | Generic SPIs — nothing domain-specific |
| **Layer 2: infra domain** | Resource types, provisioning semantics, infrastructure state | Infrastructure concepts — not which tool executes |
| **Layer 3: backends** | How to talk to Terraform, Ansible, or cloud APIs | Tool-specific execution |

Each layer only knows about the layer below it. The desiredstate runtime has no idea `InfraBackend` exists. The `InfraNodeProvisioner` has no idea whether Terraform or a cloud SDK is underneath.

### 2.2 CaseHub Owns Orchestration and Governance, Tools Own Execution

CaseHub's value is cross-resource ordering, governance (approval gates, audit trail, trust), continuous reconciliation, and fault policy. Terraform's value is its provider ecosystem and holistic workspace planning. Ansible's value is agentless host configuration. The design does not replicate what tools already do well — it adds what they lack.

### 2.3 Maximise Foundation Reuse

The infra module is thin — it contains only infrastructure-domain-specific mappings. Generic concerns (plan/apply lifecycle, risk classification, drift reporting, reconciliation scheduling, trust scoring) belong in foundation repos and are consumed, not rebuilt.

### 2.4 Typed Domain Model

No `Map<String, Object>` for data passing. All specs, contexts, and results are domain-typed Java records with sealed hierarchies for polymorphism and pattern matching. Escape hatches for genuinely dynamic data (`JsonNode`, typed wrapper records) are explicit and named.

### 2.5 Task Execution is Orthogonal

The "make this thing exist" step (calling a cloud API, running a CLI command, executing a script) is completely separated from orchestration. Multiple task execution strategies coexist behind a thin SPI. This includes LLM-generated provisioning as a first-class strategy.

### 2.6 Reactive SPIs for I/O-Bound Operations

All SPI methods that perform I/O return `Uni<T>` (Mutiny). This is a platform constraint learned from casehub-iot: the IoT `DeviceProvider` was originally blocking, then corrected to `Uni<>` during C3 implementation (see IoT ARC42STORIES §2 Constraints). Infrastructure operations — cloud API calls, `terraform plan/apply`, SSH connections, K8s API calls — are all I/O-bound and must not block the Vert.x event loop.

### 2.7 Separate WHAT from HOW

Resource specifications describe what should exist (a K8s namespace, a database cluster). They carry no knowledge of which tool provisions them. Backend routing is an operational concern resolved during goal compilation and carried as a wrapper around the resource spec — not baked into the domain types themselves. This enables reuse: other domains can use the same typed specs without the InfraBackend concept.

---

## 3. Architecture

### 3.1 Three Operating Modes

The infra module supports three modes, all behind the same `InfraBackend` SPI:

**Standalone (CaseHub-native):** CaseHub manages infrastructure directly. Full lifecycle ownership: provisioning via cloud SDKs/CLIs/LLM-generated scripts, state management in CaseHub's own store, drift detection via API polling and event subscriptions. No external tools required. This is the research baseline — what does CaseHub's desired-state model look like unencumbered by wrapping another tool's execution model?

**Terraform augmentation:** CaseHub wraps and orchestrates Terraform. Two sub-modes:
- *Generative:* CaseHub goal declaration → `InfraGoalCompiler` → `DesiredStateGraph` → `TerraformBackend` generates HCL → `terraform plan/apply`. This leverages Terraform's 1,500+ provider ecosystem at zero maintenance cost — CaseHub generates the HCL, Terraform's providers handle the actual cloud API calls. The value over standalone mode is ecosystem coverage without building SDK integrations.
- *Wrapping:* Operator brings existing `.tf` files and state. CaseHub orchestrates `terraform plan/apply` around them, adding governance, continuous reconciliation, and audit.

**Ansible augmentation:** CaseHub wraps and orchestrates Ansible. Same two sub-modes:
- *Generative:* CaseHub generates playbooks from node specs.
- *Wrapping:* Operator brings existing playbooks. CaseHub adds governance.

**Mixed mode:** Different nodes in the same `DesiredStateGraph` can use different backends. Terraform provisions the network layer, CaseHub standalone deploys K8s workloads, Ansible configures host software — all in one graph with CaseHub handling cross-backend ordering and governance.

### 3.2 SPI Landscape

```
casehub-desiredstate (generic runtime)
    │
    │  GoalCompiler, ActualStateAdapter, NodeProvisioner,
    │  FaultPolicy, EventSource
    │
    ▼
casehub-ops-infra (implements all five SPIs)
    │
    │  InfraGoalCompiler        → YAML goals → DesiredStateGraph + InfraDesiredNodeSpec wrappers
    │  InfraActualStateAdapter  → iterates desired nodes, delegates per-node to InfraBackend.readState()
    │  InfraNodeProvisioner     → unwraps InfraDesiredNodeSpec to get backendId + InfraNodeSpec,
    │                             dispatches to InfraBackend with domain types
    │  InfraFaultPolicy
    │  InfraEventSource         → passive hot Multi<StateEvent> stream with emit()/emitDrift();
    │                             periodic polling deferred to ReconciliationLoop (casehub-desiredstate#19)
    │
    ▼
InfraBackend SPI (infra-domain-internal, reactive Uni<T>)
    │
    ├── StandaloneBackend    → ResourceProvisioner SPI for task execution
    ├── TerraformBackend     → terraform CLI or Terraform Cloud API
    └── AnsibleBackend       → ansible-playbook CLI or AWX API
```

### 3.3 Component Map

| Component | Module | Layer | Role |
|-----------|--------|-------|------|
| `InfraNodeSpec` (sealed hierarchy) | casehub-ops-api | Domain types | Typed resource specifications — tool-neutral |
| `InfraDesiredNodeSpec` | casehub-ops-api | Composite NodeSpec | Wraps InfraNodeSpec + backendId — WHAT + HOW |
| `InfraBackend` | casehub-ops-api | SPI | Reactive provisioning + state + drift per tool |
| `InfraProvisionContext` | casehub-ops-api | Domain context | Phase, action, approved plan, risk thresholds, tenant |
| `ResourceProvisioner` | casehub-ops-api | SPI | Task execution strategies (standalone only) |
| `InfraGoalCompiler` | casehub-ops-infra | GoalCompiler impl | YAML goals → DesiredStateGraph + InfraDesiredNodeSpec |
| `InfraNodeProvisioner` | casehub-ops-infra | NodeProvisioner impl | Unwraps InfraDesiredNodeSpec, dispatches to InfraBackend |
| `InfraActualStateAdapter` | casehub-ops-infra | ActualStateAdapter impl | Iterates desired nodes, delegates per-node to InfraBackend.readState() |
| `InfraFaultPolicy` | casehub-ops-infra | FaultPolicy impl | Infra-specific fault rules |
| `InfraEventSource` | casehub-ops-infra | EventSource impl | Passive hot `Multi<StateEvent>` stream; `emit()`/`emitDrift()` for external callers; periodic polling deferred to ReconciliationLoop |
| `StandaloneBackend` | casehub-ops-infra | InfraBackend impl | CaseHub-native provisioning |
| `TerraformBackend` | casehub-ops-infra | InfraBackend impl | Terraform CLI/API integration |
| `AnsibleBackend` | casehub-ops-infra | InfraBackend impl | Ansible CLI/API integration |

---

## 4. Core Types

### 4.1 InfraNodeSpec — Sealed Type Hierarchy (WHAT)

Resource specs describe WHAT should exist — they carry no knowledge of which backend provisions them. Well-known resource types have fully typed Java records. An explicit generic fallback exists for wrapping arbitrary Terraform/Ansible resources where CaseHub has no typed model.

```java
sealed interface InfraNodeSpec
    permits ComputeInstanceSpec, DatabaseClusterSpec,
            K8sNamespaceSpec, K8sDeploymentSpec, K8sServiceSpec, K8sIngressSpec,
            TerraformWorkspaceSpec, AnsiblePlaybookSpec,
            GenericResourceSpec {

    String resourceType();
}
```

`InfraNodeSpec` does NOT extend `NodeSpec`. Only `InfraDesiredNodeSpec` (§4.2) implements `NodeSpec`. This makes the type system enforce correct usage at compile time:

```java
// Compile error — K8sNamespaceSpec is not a NodeSpec
DesiredNode node = new DesiredNode(id, type, new K8sNamespaceSpec("ns", labels), false);

// Correct — wrapper enforced
DesiredNode node = new DesiredNode(id, type,
    new InfraDesiredNodeSpec(new K8sNamespaceSpec("ns", labels), "standalone"), false);
```

If `InfraNodeSpec` extended `NodeSpec`, the first line would compile and fail at runtime with a `ClassCastException` when `InfraNodeProvisioner` casts to `InfraDesiredNodeSpec`. Removing the extends makes the separation structural — no call site outside `InfraGoalCompiler` needs `InfraNodeSpec` to be a `NodeSpec`.

**Well-known resource types (proof-of-concept set):**

```java
record K8sNamespaceSpec(
    String name,
    Labels labels
) implements InfraNodeSpec {
    public String resourceType() { return "k8s_namespace"; }
}

record K8sDeploymentSpec(
    String namespace,
    String name,
    String image,
    int replicas,
    ResourceRequirements resources,
    Labels labels
) implements InfraNodeSpec {
    public String resourceType() { return "k8s_deployment"; }
}

record K8sServiceSpec(
    String namespace,
    String name,
    int port,
    int targetPort,
    ServiceType serviceType,
    Labels labels
) implements InfraNodeSpec {
    public String resourceType() { return "k8s_service"; }
}

record K8sIngressSpec(
    String namespace,
    String name,
    String host,
    List<IngressRule> rules,
    Labels labels
) implements InfraNodeSpec {
    public String resourceType() { return "k8s_ingress"; }
}

record ComputeInstanceSpec(
    CloudProvider provider,
    String region,
    InstanceType instanceType,
    String imageId,
    NetworkConfig network
) implements InfraNodeSpec {
    public String resourceType() { return "compute_instance"; }
}

record DatabaseClusterSpec(
    DatabaseEngine engine,
    String version,
    ClusterSize size,
    String region,
    BackupConfig backup
) implements InfraNodeSpec {
    public String resourceType() { return "database_cluster"; }
}
```

**Wrapping types (existing tool artifacts as nodes):**

These are inherently tool-bound — a Terraform workspace IS a Terraform concept. The `InfraGoalCompiler` hardcodes the backend routing for these types.

```java
record TerraformWorkspaceSpec(
    String workspacePath,
    TerraformBackendConfig state
) implements InfraNodeSpec {
    public String resourceType() { return "terraform_workspace"; }
}

record AnsiblePlaybookSpec(
    String playbookPath,
    AnsibleInventory inventory,
    AnsibleExtraVars extraVars
) implements InfraNodeSpec {
    public String resourceType() { return "ansible_playbook"; }
}
```

**Generic fallback:**

```java
record GenericResourceSpec(
    String resourceType,
    JsonNode config
) implements InfraNodeSpec {}
```

### 4.2 InfraDesiredNodeSpec — Composite Wrapper (WHAT + HOW)

The desiredstate runtime's `DesiredNode.spec()` returns an opaque `NodeSpec`. The infra domain wraps its tool-neutral `InfraNodeSpec` with backend routing metadata in a composite:

```java
record InfraDesiredNodeSpec(
    InfraNodeSpec resourceSpec,  // WHAT — the typed resource description
    String backendId             // HOW — "standalone", "terraform", "ansible"
) implements NodeSpec {}
```

This resolves backend routing without requiring changes to the generic runtime. `NodeSpec` is already domain-specific and opaque — the runtime never inspects it. The `InfraGoalCompiler` produces `DesiredNode` instances with `InfraDesiredNodeSpec` as the spec. The `InfraNodeProvisioner` unwraps it:

```java
// In InfraNodeProvisioner.provision(DesiredNode node, ProvisionContext ctx)
if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
    return Uni.createFrom().item(new ProvisionResult.Failed(node.id(), "spec is not InfraDesiredNodeSpec", false));
}
var resourceSpec = wrapper.resourceSpec();  // domain-typed WHAT
var backendId = wrapper.backendId();         // routing HOW
var backend = resolveBackend(backendId);     // CDI lookup
```

**Why not metadata on DesiredNode?** Adding a `Map<String, String>` metadata field to the generic runtime type would work but introduces an untyped map on a runtime type that most domains won't use for routing. IoT's routing is implicit from discovery (CdiDeviceRegistry knows which provider discovered each device). Deployment's routing could be qualifier-based. Only infra needs explicit per-node backend declaration. The wrapper approach keeps this domain-internal and type-safe.

### 4.3 Supporting Domain Types

```java
record Labels(Map<String, String> values) {
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }
}

enum ServiceType { CLUSTER_IP, NODE_PORT, LOAD_BALANCER }

record IngressRule(String path, String serviceName, int servicePort) {}

enum CloudProvider { AWS, GCP, AZURE, OPENSTACK }

enum DatabaseEngine { POSTGRESQL, MYSQL, MONGODB }

record InstanceType(String family, String size) {}

record ClusterSize(int nodes, String storageClass) {}

record ResourceRequirements(String cpuRequest, String cpuLimit,
                            String memoryRequest, String memoryLimit) {}

record NetworkConfig(String vpcId, String subnetId,
                     List<String> securityGroups) {}

record BackupConfig(boolean enabled, int retentionDays,
                    String schedule) {}

record TerraformBackendConfig(TerraformStateType type,
                              String bucket, String key) {}

enum TerraformStateType { LOCAL, S3, GCS, AZURE_BLOB, CONSUL, REMOTE }

record AnsibleInventory(String path, String hostGroup) {}

record AnsibleExtraVars(Map<String, String> values) {
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }
}
```

**Note on cloud-provider-specific terminology:** `InstanceType(family, size)` uses AWS naming conventions (`m5.xlarge`). GCP uses machine types (`n1-standard-4`), Azure uses VM sizes. `NetworkConfig` similarly uses AWS naming (`vpcId`, `subnetId`, `securityGroups`). For the K8s-focused PoC this is acceptable. When cloud resource types are added in the next phase, these types will need generalization — likely a sealed hierarchy per cloud provider or a provider-agnostic abstraction.

### 4.4 ResourceState — Typed Infra State

```java
record ResourceState(
    NodeId nodeId,
    String resourceType,
    ResourceStatus status,
    Instant lastObserved,
    JsonNode attributes,
    ResourceOutputs outputs
) {}

record ResourceOutputs(Map<String, JsonNode> values) {
    public Optional<JsonNode> get(String key) {
        return Optional.ofNullable(values.get(key));
    }
    public Optional<String> getString(String key) {
        return get(key).filter(JsonNode::isTextual).map(JsonNode::asText);
    }
    public static ResourceOutputs empty() {
        return new ResourceOutputs(Map.of());
    }
}

enum ResourceStatus {
    HEALTHY,
    DRIFTED,
    DEGRADED,
    UNAVAILABLE,
    PROVISIONING,
    UNKNOWN
}
```

### 4.5 DriftReport — Drift Detection Result

```java
record DriftReport(
    NodeId nodeId,
    boolean drifted,
    List<DriftedField> drifts,
    Instant detectedAt,
    String backendId
) {}

record DriftedField(
    String field,
    String expected,
    String actual
) {}
```

The `InfraEventSource` converts a `DriftReport` with `drifted=true` into a `StateEvent` for the reconciliation loop. The structured `drifts` list is not carried through `StateEvent` (which carries only nodeId + eventType + timestamp) — it is consumed during the re-provisioning path: when the reconciliation loop triggers re-provision, the provisioner calls `backend.plan()` which produces a `ProvisionPlan` with risk classification based on what actually changed. The drift detail thus informs risk assessment through the plan, not through the fault policy.

### 4.6 InfraProvisionContext — Typed Domain Context

The desiredstate runtime's `ProvisionContext` carries `NodeId`, `tenancyId`, and `Optional<PlanApproval>` (see §12.1 "Types already implemented"). The infra domain defines its own typed context for the InfraBackend boundary.

Note: `io.casehub.api.model.ProvisionContext` in casehub-engine-api is an unrelated type (carries `caseId`, `taskType`, `workerContext`). The infra context is a separate concept in `io.casehub.ops.api`.

```java
record InfraProvisionContext(
    NodeId nodeId,
    String tenancyId,
    ProvisionPhase phase,
    ProvisionAction action,
    ProvisionPlan approvedPlan,   // non-null for APPLY phase
    RiskThresholds thresholds,
    Instant requestedAt
) {}

enum ProvisionPhase { PLAN, APPLY }

enum ProvisionAction { PROVISION, DEPROVISION }

// ProvisionAction is NOT redundant — plan() receives InfraProvisionContext and needs
// action to know whether it's planning a provision or a deprovision. The method
// signature alone doesn't encode this.
// ProvisionPhase IS redundant with the call site (plan() is always PLAN, provision()
// is always APPLY) but is intentional self-documentation for logging and debugging.

enum RiskClassification { LOW, MEDIUM, HIGH, CRITICAL }

record RiskThresholds(
    RiskClassification autoApproveBelow,
    boolean requireSecondReviewer
) {}
```

### 4.7 InfraGoals — Parsed YAML Input

The parsed representation of the YAML goal declaration before compilation into a `DesiredStateGraph`.

```java
record InfraGoals(
    String defaultBackend,
    List<ResourceDeclaration> resources,
    List<ImportDeclaration> imports
) {}

record ResourceDeclaration(
    String id,
    String type,
    String backend,         // per-resource override, nullable
    JsonNode config,        // validated against InfraNodeSpec hierarchy during compilation
    List<String> dependsOn
) {}

record ImportDeclaration(
    String id,
    String type,            // "terraform_workspace" or "ansible_playbook"
    JsonNode config,
    List<String> dependsOn
) {}
```

---

## 5. InfraBackend SPI

Single cohesive SPI per tool — bundles provisioning, state, and drift because these are inherently coupled per execution backend. All methods return `Uni<T>` — every operation is I/O-bound. All methods take domain types — no coupling to the desiredstate runtime.

```java
interface InfraBackend {
    String backendId();

    Uni<BackendProvisionResult> provision(InfraNodeSpec spec,
                                          InfraProvisionContext context);

    Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec,
                                              InfraProvisionContext context);

    Uni<ResourceState> readState(NodeId nodeId);

    Uni<DriftReport> detectDrift(NodeId nodeId);

    Uni<Optional<ProvisionPlan>> plan(InfraNodeSpec spec,
                                      InfraProvisionContext context);
}
```

### 5.1 Backend Result Types

```java
sealed interface BackendProvisionResult
    permits BackendProvisionResult.Provisioned,
            BackendProvisionResult.Failed {

    record Provisioned(ResourceState state) implements BackendProvisionResult {}
    record Failed(String reason, boolean retryable) implements BackendProvisionResult {}
}

sealed interface BackendDeprovisionResult
    permits BackendDeprovisionResult.Deprovisioned,
            BackendDeprovisionResult.Failed {

    record Deprovisioned(NodeId nodeId) implements BackendDeprovisionResult {}
    record Failed(String reason, boolean retryable) implements BackendDeprovisionResult {}
}
```

These are distinct from the runtime's `ProvisionResult` / `DeprovisionResult`. The `InfraNodeProvisioner` maps between them, keeping backend implementations decoupled from runtime result semantics.

### 5.2 Backend Routing

Backend routing is resolved by the `InfraGoalCompiler` and stored in the `InfraDesiredNodeSpec` wrapper (§4.2). The `InfraNodeProvisioner` reads `backendId` from the wrapper and dispatches.

**Routing rules:**

1. **Wrapping types** — `TerraformWorkspaceSpec` always routes to `"terraform"`, `AnsiblePlaybookSpec` always routes to `"ansible"`. Hardcoded at compile time.
2. **Per-resource `backend:` field in YAML** — explicit operator choice.
3. **Top-level `backend:` default in YAML** — applies to all resources that don't override.
4. **Fallback** — `"standalone"` if no backend is specified anywhere.

**Validation:** The compiler rejects type-incompatible backend assignments. A `terraform_workspace` type with `backend: ansible` is a compile error. Well-known resource types (K8s, compute, database) accept any backend.

**CDI discovery model:** All backends are `@ApplicationScoped` and discovered via `@Any Instance<InfraBackend>`. They coexist — the dispatcher looks up by `backendId()`. This is the same model as casehub-iot's `DeviceProvider` with `providerId()`.

### 5.3 StandaloneBackend

CaseHub-native provisioning. Owns the full lifecycle without external tools.

- **Provisioning:** delegates to `ResourceProvisioner` SPI for task execution
- **State:** CaseHub-managed persistent store (the infra module's own state, not proxied)
- **Drift detection:** API polling (cloud SDK status calls, K8s API watches) and event subscriptions (CloudTrail, K8s informers)
- **Plan:** diffs desired spec against actual state to produce a structured change set

### 5.4 TerraformBackend

Wraps and augments Terraform. The generative sub-mode exists because Terraform's provider ecosystem (1,500+ providers maintained by HashiCorp and the community) covers every major cloud API. Generating HCL and delegating to Terraform leverages this ecosystem at zero maintenance cost — CaseHub doesn't need SDK integrations for every cloud resource type.

- **Provisioning (generative):** generates HCL from `InfraNodeSpec`, writes to a per-node Terraform workspace, runs `terraform apply`
- **Provisioning (wrapping):** operates on existing `.tf` directory from `TerraformWorkspaceSpec`, runs `terraform apply`
- **State:** proxies `terraform show -json` — translates Terraform state into `ResourceState`
- **Drift detection:** periodic `terraform plan` — any planned changes indicate drift
- **Plan:** `terraform plan -json` output wrapped as `ProvisionPlan`
- **Execution:** local CLI (`terraform`) or remote API (Terraform Cloud/Enterprise)

Each CaseHub node maps to one Terraform workspace (generative mode) or one CaseHub node wraps one existing workspace (wrapping mode). No workspace grouping logic needed.

### 5.5 AnsibleBackend

Wraps and augments Ansible.

- **Provisioning (generative):** generates playbook YAML from `InfraNodeSpec`, runs `ansible-playbook`
- **Provisioning (wrapping):** runs existing playbook from `AnsiblePlaybookSpec`
- **State:** live system queries via Ansible facts — no persistent Ansible state to proxy
- **Drift detection:** `ansible-playbook --check --diff` — tasks that would execute indicate drift
- **Plan:** check-mode output wrapped as `ProvisionPlan`
- **Execution:** local CLI (`ansible-playbook`) or remote API (AWX/Tower)

---

## 6. ResourceProvisioner SPI — Task Execution (Standalone Backend Only)

Task execution is orthogonal to orchestration. The `StandaloneBackend` delegates actual "make this thing exist" work to `ResourceProvisioner` implementations. All methods return `Uni<T>`.

```java
interface ResourceProvisioner {
    String provisionerId();
    boolean handles(InfraNodeSpec spec);
    Uni<ProvisionOutcome> execute(ProvisionTask task);
}
```

```java
record ProvisionTask(
    NodeId nodeId,              // which node is being provisioned
    InfraNodeSpec spec,
    TaskAction action,
    ResourceState currentState  // null for CREATE
) {}

enum TaskAction { CREATE, UPDATE, DESTROY }

record ProvisionOutcome(
    boolean success,
    ResourceState resultState,
    String executionLog,
    ExecutionArtifact artifact
) {}
```

If no `ResourceProvisioner.handles()` returns true for a given spec, the `StandaloneBackend` must fail with `BackendProvisionResult.Failed("No provisioner handles resource type: " + spec.resourceType(), false)`. This is not retryable — it indicates a configuration error, not a transient failure.

### 6.1 Selection Precedence

Multiple `ResourceProvisioner` strategies can handle the same spec (e.g., a `K8sDeploymentSpec` could be handled by `K8sProvisioner`, `CliProvisioner`, and `LlmProvisioner`). Selection uses CDI priority with Preferences override:

- **Default:** `@Priority(N)` ordering — highest priority provisioner that `handles()` the spec wins. `K8sProvisioner @Priority(100)` beats `CliProvisioner @Priority(50)` for K8s specs.
- **Override:** casehub-platform `PreferenceKey<String>` per resource type allows operators to force a specific provisioner (e.g., force `LlmProvisioner` for `compute_instance` during research evaluation).

### 6.2 Strategies

| Strategy | Implementation | Use case | Priority |
|----------|---------------|----------|----------|
| `K8sProvisioner` | Fabric8 K8s Java client | K8s resources — mature Java ecosystem, no CLI | 100 |
| `SdkProvisioner` | AWS SDK, GCP client libraries | Type-safe, no CLI dependency | 80 |
| `CliProvisioner` | Shells out to `aws`, `gcloud`, `az` | Quick, pragmatic — CLIs are well-documented | 50 |
| `ScriptProvisioner` | Executes a provided script | Maximum flexibility | 30 |
| `LlmProvisioner` | Generates provisioning commands from spec on demand | Zero maintenance burden | 10 |

### 6.3 LLM Provisioner — Generate, Cache, Trust, Reuse

The `LlmProvisioner` follows a four-stage lifecycle:

1. **Generate:** LLM produces provisioning commands from the `InfraNodeSpec`. The spec is typed, so the prompt is structured. Ledger records: model, spec hash, generated artifact hash.
2. **Cache:** Generated artifact is cached keyed by `(resourceType, specHash, action)`. The `specHash` is a content hash of the full spec record. Different specs (e.g. 3 replicas vs 5 replicas) produce different artifacts. Different actions (CREATE vs DESTROY) produce different artifacts.
3. **Review:** Cached artifact flows through a human review WorkItem. Reviewer approves, modifies, or rejects. Approval recorded in ledger.
4. **Reuse with trust:** Subsequent identical `(resourceType, specHash, action)` tuples hit the cache. Trust score accumulates with each successful execution (casehub-ledger Bayesian Beta model applied to artifacts). Low trust → human gate. High trust → auto-approved.

Cache invalidation: execution failures or manual invalidation trigger regeneration and re-review.

**`ExecutionArtifact` supports this lifecycle:**

```java
record ExecutionArtifact(
    String contentHash,
    ArtifactType type,
    String content,
    ArtifactProvenance provenance
) {}

enum ArtifactType { SCRIPT, CLI_COMMAND, SDK_CALL }

sealed interface ArtifactProvenance
    permits HandWritten, LlmGenerated, CachedReuse {}

record LlmGenerated(String model, Instant generatedAt,
                    String specHash) implements ArtifactProvenance {}
record CachedReuse(String originalHash,
                   Instant cachedAt) implements ArtifactProvenance {}
record HandWritten(String author) implements ArtifactProvenance {}
```

---

## 7. Goal Compilation

### 7.1 InfraGoalCompiler

Implements `GoalCompiler<InfraGoals>`. Accepts YAML goal declarations (parsed into `InfraGoals`) and produces a `DesiredStateGraph` where each node's spec is an `InfraDesiredNodeSpec` wrapping the tool-neutral resource spec with backend routing.

Note on naming: the desiredstate runtime defines the `GoalCompiler` SPI with the intention that domains can support high-level goal expansion (e.g., "I want a production-ready web app" → compiler expands to namespace + deployment + service + ingress + database). The PoC starts with direct resource manifests. The GoalCompiler interface supports both — the level of abstraction is the compiler's choice, not a constraint of the SPI.

```java
class InfraGoalCompiler implements GoalCompiler<InfraGoals> {
    DesiredStateGraph compile(InfraGoals goals, Constraints constraints,
                             DomainData data) {
        // For each ResourceDeclaration / ImportDeclaration:
        // 1. Parse config into typed InfraNodeSpec (sealed hierarchy)
        // 2. Resolve backendId (per-resource > top-level > "standalone")
        // 3. Validate backend compatibility with resource type
        // 4. Wrap in InfraDesiredNodeSpec(resourceSpec, backendId)
        // 5. Create DesiredNode with InfraDesiredNodeSpec as spec
        // 6. Create Dependency edges from dependsOn
    }
}
```

### 7.2 Goal Declaration Format (YAML)

**Generative mode — declare resources:**

```yaml
infra:
  backend: standalone           # top-level default — applies unless overridden
  resources:
    - type: k8s_namespace
      id: app-ns
      config:
        name: my-app

    - type: database_cluster
      id: app-db
      backend: terraform        # per-resource override — uses TerraformBackend
      config:
        engine: postgresql
        version: "16"
        size: { nodes: 1, storageClass: standard }
        region: us-east-1
        backup: { enabled: true, retentionDays: 7, schedule: "0 2 * * *" }
      dependsOn: [app-ns]

    - type: k8s_deployment
      id: app-deploy
      config:
        namespace: my-app
        name: my-app
        image: my-app:latest
        replicas: 3
        resources: { cpuRequest: "100m", cpuLimit: "500m", memoryRequest: "128Mi", memoryLimit: "512Mi" }
      dependsOn: [app-db, app-ns]

    - type: k8s_service
      id: app-svc
      config:
        namespace: my-app
        name: my-app
        port: 8080
        targetPort: 8080
        serviceType: CLUSTER_IP
      dependsOn: [app-deploy]
```

**Wrapping mode — import existing artifacts:**

```yaml
infra:
  imports:
    - type: terraform_workspace       # backend implicit — terraform
      id: prod-network
      config:
        workspacePath: ./terraform/networking
        state: { type: S3, bucket: my-tfstate, key: networking }

    - type: ansible_playbook          # backend implicit — ansible
      id: app-config
      config:
        playbookPath: ./ansible/configure-app.yml
        inventory: { path: ./ansible/inventory, hostGroup: app-servers }
      dependsOn: [prod-network]
```

**Mixed mode — both in one graph:**

```yaml
infra:
  imports:
    - type: terraform_workspace
      id: network-layer
      config:
        workspacePath: ./terraform/networking
        state: { type: LOCAL }

  resources:
    - type: k8s_deployment
      id: my-app
      backend: standalone
      config:
        namespace: my-app
        name: my-app
        image: my-app:latest
        replicas: 3
        resources: { cpuRequest: "100m", cpuLimit: "500m", memoryRequest: "128Mi", memoryLimit: "512Mi" }
      dependsOn: [network-layer]
```

### 7.3 YAML to Typed Spec Mapping

The `InfraGoalCompiler` validates YAML against the sealed `InfraNodeSpec` hierarchy:
- Known `type` values (e.g. `k8s_namespace`) map to typed records (e.g. `K8sNamespaceSpec`) with full validation
- Unknown `type` values map to `GenericResourceSpec` with a `JsonNode` config — structured but untyped
- Wrapping types (`terraform_workspace`, `ansible_playbook`) map to their respective typed specs with hardcoded backend routing
- `dependsOn` entries become `Dependency` edges in the `DesiredStateGraph`
- `backend:` (per-resource or top-level) becomes the `backendId` field in the `InfraDesiredNodeSpec` wrapper
- **Validation:** `backend: ansible` on a `terraform_workspace` type is a compile error. Well-known resource types accept any backend.

---

## 8. State Management

### 8.1 Uniform ResourceState

All backends produce `ResourceState` through the same type. The `InfraActualStateAdapter` iterates over all desired nodes in the graph, unwraps each `InfraDesiredNodeSpec` to determine the backend, and calls the appropriate `InfraBackend.readState()`. Results are mapped to the runtime's `NodeStatus` enum and collected into `ActualState`.

**Status mapping (ResourceStatus → NodeStatus):**

| ResourceStatus | NodeStatus | Rationale |
|---|---|---|
| HEALTHY | PRESENT | Resource exists and matches desired state |
| DRIFTED | DRIFTED | Resource exists but diverges from desired |
| UNAVAILABLE | ABSENT | Resource unreachable or destroyed |
| DEGRADED | UNKNOWN | Lossy mapping — see note below |
| PROVISIONING | UNKNOWN | Lossy mapping — see note below |
| UNKNOWN | UNKNOWN | State undetermined |
| readState failure | UNKNOWN | Backend error — fail safe |

**Known lossy mappings:** DEGRADED maps to UNKNOWN rather than DRIFTED. A degraded node hasn't met its spec, so DRIFTED (which triggers re-provisioning) would be more correct. However, the runtime's UNKNOWN means "skip/retry later" which is also a valid response to degradation. PROVISIONING maps to UNKNOWN because there's no "in progress" variant — the runtime should not re-provision a node that's actively being provisioned. The runtime's `NodeStatus` enum is too coarse for the infra domain's state space. Consider proposing `DEGRADED` and `IN_PROGRESS` variants if this causes incorrect remediation behavior.

### 8.2 State Backend Per Mode

| Mode | State source | State ownership |
|------|-------------|-----------------|
| Standalone | CaseHub-managed store (persistent) | CaseHub owns state |
| Terraform | `terraform show -json` proxy | Terraform owns state, CaseHub reads |
| Ansible | Live system queries (Ansible facts) | No persistent state — live system IS the state |

### 8.3 Sensitive Data

Terraform state files may contain secrets (database passwords, API keys). The `TerraformBackend` must sanitise sensitive fields before surfacing them through `ResourceState`. The `attributes` field in `ResourceState` carries sanitised data only — secrets are never stored in the audit trail or exposed through the API.

---

## 9. Drift Detection and Continuous Reconciliation

### 9.1 Per-Backend Drift Detection

| Backend | Primary mechanism | Secondary mechanism |
|---------|------------------|---------------------|
| Standalone | Cloud event subscriptions (CloudTrail, K8s informers) | Periodic API polling as fallback |
| Terraform | Periodic `terraform plan` | Cloud events for faster detection of critical changes |
| Ansible | Periodic `ansible-playbook --check` | Host-level monitoring |

### 9.2 Reconciliation Flow

1. `InfraEventSource` detects drift (event or poll) → fires `StateEvent` into generic reconciliation loop
2. Loop diffs desired vs actual → identifies drifted nodes
3. `InfraFaultPolicy` evaluates: auto-remediate, human escalation, or ignore?
4. If remediate → re-provisions the drifted node via `InfraNodeProvisioner`
5. If human → creates WorkItem with drift details
6. All actions ledgered — tamper-evident record of what drifted, when, what was done

### 9.3 Reconciliation Frequency and Event Emission

Event-driven sources (K8s informers, CloudTrail): real-time — events flow directly into the `Multi<StateEvent>` stream.

Polling-based sources (`terraform plan`, API queries): configurable interval per node or per resource type via casehub-platform Preferences. The `InfraEventSource` runs periodic polls via a scheduled timer, invokes `InfraBackend.detectDrift()`, and emits the result as a `StateEvent` into the same `Multi<StateEvent>` stream.

**Event deduplication:** Both real-time events and periodic polls may detect the same drift. Duplicate `StateEvent` emissions for the same `(nodeId, driftedField)` within a configurable time window are expected. Deduplication is a desiredstate runtime concern — the reconciliation loop should suppress duplicate events by `(nodeId, timestamp window)` before triggering remediation.

**PoC limitation:** The `InfraEventSource` implementation is passive — it exposes `emit()` and `emitDrift()` methods for external callers to push events into the stream, but has no internal scheduler for periodic polling. Drift detection today requires something to call `emitDrift()` — which in practice means only test code. Poll-based drift detection depends on the `ReconciliationLoop` (not yet implemented) calling `InfraBackend.detectDrift()` on a schedule and pushing results into the event source. This is tracked in casehub-desiredstate#19.

The spec's description of periodic polling describes the target architecture, not the current PoC state.

---

## 10. Fault Policy

### 10.1 Default Fault Rules

The `FaultPolicy` SPI returns `Optional<GraphMutation>` — it can only express graph-level changes (add/remove/update nodes). Runtime behaviors like retry, WorkItem creation, human escalation, and re-provisioning are driven by the `ReconciliationLoop` (which does not yet exist — see §12.1). The table below separates these two concerns:

**FaultPolicy responses (what InfraFaultPolicy returns):**

| Fault type | GraphMutation | Rationale |
|------------|--------------|-----------|
| `DRIFT_DETECTED` | empty — no graph change | Runtime handles re-provisioning |
| `PROVISION_FAILED_TRANSIENT` | empty — no graph change | Runtime handles retry with backoff |
| `PROVISION_FAILED_PERMANENT` | `RemoveNode(nodeId)` | Node can't be provisioned — remove from desired graph |
| `NODE_DESTROYED` | empty — no graph change | Runtime handles re-provisioning |
| `BACKEND_UNHEALTHY` | empty — no graph change | Runtime handles suspension and escalation |
| Unknown types | empty — no graph change | Safe default — no mutation |

**Expected runtime behaviors (ReconciliationLoop, not FaultPolicy):**

| Fault | Expected runtime behavior | Depends on |
|-------|--------------------------|------------|
| Provision fails (transient) | Retry with backoff (max 3) | ReconciliationLoop retry policy |
| Drift detected | Re-provision to desired state | ReconciliationLoop re-provision cycle |
| Resource destroyed externally | Re-provision if desired state still wants it | ReconciliationLoop re-provision cycle |
| Backend unhealthy | Suspend affected nodes, create human WorkItem | ReconciliationLoop + casehub-work |
| Plan shows destructive changes | Require human approval WorkItem | Plan/apply lifecycle (PendingApproval) |
| Trust score below threshold | Require human co-approval | casehub-ledger trust integration |

The `GraphMutation` vocabulary (AddNode, RemoveNode, UpdateNode) does not express suspend, escalate, or WorkItem creation. These are runtime orchestration concerns that belong in the ReconciliationLoop, not in the FaultPolicy SPI. The infra module's FaultPolicy is intentionally limited to graph mutations — the runtime drives everything else.

**Note on intentional drift:** The PoC treats all drift as unintentional and triggers re-provision. Intentional drift detection is a production concern requiring operator UX design. Deferred.

### 10.2 Risk Classification

Applied to `ProvisionPlan` to determine whether human approval is required:

| Level | Criteria | Gate |
|-------|----------|------|
| LOW | Additive changes to non-production | Auto-approve with audit |
| MEDIUM | Modifications to non-production, additions to production | Auto-approve with audit |
| HIGH | Modifications to production resources | Human approval WorkItem |
| CRITICAL | Destructive changes to production | Human approval + plan artifact for review |

Classification derived from: environment tags, change type (create/modify/destroy), resource type, and custom rules in the goal declaration.

### 10.3 Composition

Multiple fault policies compose by CDI priority — highest priority wins. Domain-specific policies override defaults. Unhandled faults fall through to the default.

---

## 11. Plan/Apply — Human Approval Model

### 11.1 Two-Phase Lifecycle (Provisioning and Deprovisioning)

Every backend can produce a plan before provisioning or deprovisioning. The plan is the natural human review artifact. The same two-phase model applies to both — destroying a production database is at least as dangerous as creating one.

**Provisioning flow:**

1. Runtime calls `NodeProvisioner.provision(node, ctx)`
2. `InfraNodeProvisioner` unwraps `InfraDesiredNodeSpec`, constructs `InfraProvisionContext` with `phase=PLAN`, `action=PROVISION`
3. Calls `backend.plan(resourceSpec, ctx)` → `Optional<ProvisionPlan>`
4. If plan present and `plan.risk >= threshold` → return `ProvisionResult.PendingApproval(plan)` to the runtime
5. Runtime creates WorkItem with plan artifact, waits for approval signal via `CaseSignalSink`
6. On approval → runtime re-calls `provision(node, ctx)` with approval state in the generic `ProvisionContext`
7. `InfraNodeProvisioner` constructs `InfraProvisionContext` with `phase=APPLY`, `approvedPlan` set
8. Calls `backend.provision(resourceSpec, approvedCtx)` → `BackendProvisionResult.Provisioned(state)`
9. Maps to `ProvisionResult.Provisioned` and returns to runtime

**Deprovisioning flow:** Same pattern. `InfraProvisionContext.action=DEPROVISION`. The `backend.plan()` call produces a plan with `ChangeAction.DESTROY`. Risk classification applies — destroying production resources is CRITICAL. If approved, `backend.deprovision()` executes.

### 11.2 Approval Signal — Already in Runtime API

The plan/apply flow requires the runtime to carry approval state when re-calling the provisioner after approval. This is already implemented in `casehub-desiredstate-api`:

- `ProvisionContext.approval()` returns `Optional<PlanApproval>`
- `PlanApproval(planReference, approvedBy, approvedAt)` carries the approved plan reference
- `DeprovisionContext` also carries `Optional<PlanApproval>`

The types exist. What's missing is the `ReconciliationLoop` that handles `PendingApproval` results, creates WorkItems, waits for approval via `CaseSignalSink`, and re-calls the provisioner with a populated `PlanApproval`. This is tracked as a ReconciliationLoop implementation concern in §12.1.

### 11.3 Plan Storage Between PLAN and APPLY Phases

The runtime's `PlanApproval.planReference` is an opaque `String`. The infra domain's `InfraProvisionContext.approvedPlan` is a typed `ProvisionPlan`. When the runtime re-calls after approval, the `InfraNodeProvisioner` receives a `planReference` string and must reconstruct the full `ProvisionPlan`.

**PoC approach:** `InfraNodeProvisioner` maintains an in-memory `ConcurrentHashMap<String, ProvisionPlan>` keyed by plan reference. During the PLAN phase, the generated plan is stored. During the APPLY phase, the plan reference from `PlanApproval` is used to retrieve it. This is lost on restart — acceptable for the PoC.

**Production approach:** Store plans in casehub-ledger as a tamper-evident record. The plan reference becomes a ledger entry ID. This adds a dependency on casehub-ledger-api but provides durability, auditability, and the same trust infrastructure used for all other governance artifacts.

**Plan freshness:** Infrastructure can change during the approval window (another operator modifies a resource, auto-scaling kicks in). Backends SHOULD verify plan freshness during the APPLY phase and return `BackendProvisionResult.Failed` if the plan is stale (actual state diverged from the plan's assumed starting state). This is what Terraform does: `terraform apply` with a saved plan file re-checks state and fails if it diverged. For backends that cannot verify freshness, the risk is that an approved plan is applied against a changed reality.

### 11.4 Credential Model

Backends authenticate to cloud providers, Terraform Cloud, AWX, etc. via environment configuration — environment variables, IAM roles, service accounts, Kubernetes service account tokens. Credentials are NOT passed through the provisioning API. The `InfraProvisionContext` carries no secrets.

This follows the standard cloud-native pattern: the execution environment provides authentication context. CaseHub does not manage or store cloud credentials.

### 11.5 Interaction with requiresHuman

The desiredstate runtime's `DesiredNode.requiresHuman` and the infra domain's `ProvisionResult.PendingApproval` are complementary:

- **`requiresHuman = true`:** Static declaration — "this node always needs human involvement regardless of risk." The runtime creates a WorkItem and does NOT call the provisioner. Used for inherently manual operations (e.g., physical hardware installation in the IoT domain; manual approval of a production database migration in infra).
- **`PendingApproval`:** Dynamic, risk-based — "the provisioner examined the plan and determined this specific change needs review." The provisioner IS called (to generate the plan), but defers execution until approval.

If both are active on the same node: one WorkItem, not two. The WorkItem carries both the plan artifact (what the provisioner would do) and the human-node instructions (what the human must do). The provisioner generates the plan during a pre-planning phase before the WorkItem is created.

### 11.6 ProvisionPlan

```java
record ProvisionPlan(
    NodeId nodeId,
    List<PlannedChange> changes,
    RiskClassification risk,
    String humanReadableSummary,
    ToolPlanDetail toolDetail
) {}

record PlannedChange(
    ChangeAction action,
    String resourceAddress,
    String fieldSummary
) {}

enum ChangeAction { ADD, MODIFY, DESTROY, REPLACE }

sealed interface ToolPlanDetail
    permits TerraformPlanDetail, AnsibleCheckDetail, StandaloneDiffDetail {}

record TerraformPlanDetail(JsonNode planJson) implements ToolPlanDetail {}
record AnsibleCheckDetail(String checkOutput) implements ToolPlanDetail {}
record StandaloneDiffDetail(List<FieldDiff> diffs) implements ToolPlanDetail {}

record FieldDiff(String field, String before, String after) {}
```

---

## 12. Foundation Reuse and Proposals

### 12.1 Proposals to casehub-desiredstate

**Types already implemented** (in casehub-desiredstate-api — confirmed at implementation time):
- `ProvisionResult.PendingApproval(NodeId, String planReference)` — sealed variant
- `NodeProvisioner.provision()` returns `Uni<ProvisionResult>` — fully reactive
- `ProvisionContext(NodeId, tenancyId, Optional<PlanApproval>)` — carries approval state
- `DeprovisionContext(NodeId, tenancyId, Optional<PlanApproval>)` — same for deprovision

**What's missing is runtime behavior, not types.** The ReconciliationLoop that handles PendingApproval (creates WorkItems, waits for approval, re-calls provisioner with PlanApproval populated) does not yet exist. The proposals below address behavioral gaps:

| # | Proposal | Rationale |
|---|----------|-----------|
| 1 | ReconciliationLoop — PendingApproval workflow | Implement the loop that handles `ProvisionResult.PendingApproval`: create casehub-work WorkItem with plan artifact, wait for approval via `CaseSignalSink`, re-call provisioner with `PlanApproval` populated in context. The types exist; the orchestration does not. |
| 2 | `DeprovisionResult.PendingApproval` variant | Deprovisioning can be equally dangerous (destroying production resources is CRITICAL risk). The current `DeprovisionResult` is `Deprovisioned \| Failed` — no way to signal "pending approval." Add `PendingApproval(NodeId, String planReference)` to enable plan/apply for deprovisioning. |
| 3 | Multi-provisioner dispatch | Runtime routes to NodeProvisioner by node qualifier. Eliminates domain-internal dispatchers. IoT, deployment, infra all need this pattern. |
| 4 | Reconciliation scheduling | Runtime drives periodic reconciliation (configurable per node). Domains provide event streams; runtime also drives polling. The infra InfraEventSource is currently passive — it depends on an external driver to call detectDrift(). |
| 5 | Tenant-scoped readState/detectDrift | `InfraBackend.readState(NodeId)` and `detectDrift(NodeId)` take only a NodeId. In multi-tenant scenarios, the same logical node ID could exist in multiple tenants. These methods need tenant context. PoC simplification — single tenant assumed. |

**Types that stay domain-level (NOT promoted to desiredstate-api):**

The runtime uses opaque `String planReference` in `PlanApproval` — it doesn't need to know the plan's structure. These types are infra-domain-specific and correctly live in casehub-ops-api:
- `ProvisionPlan` — plan structure varies by domain (Terraform plan JSON vs Ansible check output vs K8s diff)
- `DriftReport` — drift detail varies by domain; the runtime's `StateEvent` is the generic drift signal
- `RiskClassification` — could be platform-wide, but isn't needed at the desiredstate runtime layer

### 12.2 Proposal to casehub-ledger

| # | Proposal | Rationale |
|---|----------|-----------|
| 6 | Artifact trust scoring | Extend Bayesian Beta trust model from actors to content-hashed artifacts. Enables the generate-cache-trust-reuse lifecycle for LLM-generated provisioning scripts. Same model applies to LLM-generated configs, case definitions, remediation scripts across the platform. |

### 12.3 CaseHub Infrastructure Reused Directly

| CaseHub capability | How infra uses it |
|-------------------|-------------------|
| casehub-work WorkItems | Human approval gates for plans, drift remediation |
| casehub-ledger audit trail | Merkle-proofed record of every provision/deprovision/drift/approval |
| casehub-ledger trust scoring | Trust for provisioning actors |
| casehub-engine-flow | Transition plan execution as Serverless Workflows |
| casehub-connectors | Notifications on infra events (Slack/Teams alerts on prod drift) |
| casehub-platform Preferences | Reconciliation intervals, risk thresholds, default backends, provisioner overrides |
| CaseSignalSink | Signals reconciliation loop when human WorkItem completes |

### 12.4 CaseHub Infrastructure That Could Mature

| Capability | Current state | Enhancement for infra |
|-----------|--------------|----------------------|
| casehub-work WorkItemTemplate | Generic structured content | "Plan review" template with before/after display, approve/reject/modify |
| casehub-eidos agent identity | Describes AI agents | LLM provisioners as agents with eidos identity for trust tracking |

---

## 13. Module Structure

### 13.1 Current Layout

```
casehub-ops/
├── api/           casehub-ops-api        Tier 1 — InfraNodeSpec hierarchy,
│                                          InfraDesiredNodeSpec, InfraBackend SPI (reactive),
│                                          InfraProvisionContext, ResourceProvisioner SPI,
│                                          ResourceState, DriftReport, domain types
├── infra/         casehub-ops-infra      Tier 3 — InfraGoalCompiler, InfraNodeProvisioner,
│                                          InfraFaultPolicy, InfraEventSource,
│                                          StandaloneBackend, TerraformBackend,
│                                          AnsibleBackend
├── deployment/    (future — agent topology domain)
├── compliance/    (future — compliance posture domain)
├── iot/           (future — IoT desired state domain)
└── testing/       casehub-ops-testing    Test fixtures
```

### 13.2 Backend CDI Model

Backends are `@ApplicationScoped` and coexist — not alternatives that displace each other. Different nodes use different backends. The `InfraNodeProvisioner` discovers all backends via `@Any Instance<InfraBackend>` and routes by `backendId()`.

This matches casehub-iot's `DeviceProvider` pattern — `HomeAssistantProvider` and `OpenHABProvider` coexist, each identified by `providerId()`.

### 13.3 Future Module Extraction

If Terraform/Ansible backends grow to need heavy dependencies (Terraform Cloud REST client, AWX REST client), they can be extracted to separate modules:

```
infra/                  core + StandaloneBackend
infra-terraform/        TerraformBackend (optional, activates by classpath)
infra-ansible/          AnsibleBackend (optional, activates by classpath)
```

The `InfraBackend` SPI in `casehub-ops-api` enables this split without breaking consumers.

### 13.4 Dependency Direction

```
casehub-ops-infra → casehub-ops-api → casehub-desiredstate-api → casehub-platform-api
```

No reverse dependencies. No tier violations.

---

## 14. Comparison Matrix

| Capability | CaseHub Standalone | CaseHub + Terraform | Terraform alone |
|------------|-------------------|--------------------|-----------------| 
| Declarative intent | DesiredStateGraph | DesiredStateGraph | HCL |
| Dependency ordering | TransitionPlanner | CaseHub cross-workspace + Terraform intra-workspace | Terraform only |
| Continuous reconciliation | EventSource + polling | Periodic `terraform plan` | Manual `plan` runs |
| Human approval gates | casehub-work WorkItems | WorkItems wrapping Terraform plans | External (Atlantis/TF Cloud) |
| Fault policy | FaultPolicy SPI (configurable, composable) | FaultPolicy SPI | None (error and stop) |
| Audit trail | casehub-ledger (Merkle MMR) | casehub-ledger | State file (not tamper-evident) |
| Trust-weighted execution | casehub-ledger trust scoring | casehub-ledger trust scoring | None |
| Provider ecosystem | ResourceProvisioner strategies (SDK/CLI/LLM) | 1,500+ Terraform providers | 1,500+ providers |
| Task execution maintenance | Zero (LLM-generated) to low (SDK/CLI) | Terraform-maintained | Terraform-maintained |
| Script trust model | Generate → cache → human review → reuse with earned trust | Implicit (HashiCorp ships providers) | Implicit |

---

## 15. Implementation Scope

### 15.1 Proof-of-Concept (this issue)

- `InfraNodeSpec` sealed hierarchy with K8s types (namespace, deployment, service, ingress)
- `InfraDesiredNodeSpec` wrapper (resource spec + backend routing)
- `InfraBackend` SPI (reactive, domain-typed)
- `InfraProvisionContext` with phase/action/plan support
- `StandaloneBackend` with `K8sProvisioner` (Fabric8 client)
- `InfraGoalCompiler` for YAML goals with InfraDesiredNodeSpec production
- `InfraNodeProvisioner` dispatcher (unwraps InfraDesiredNodeSpec, dispatches to InfraBackend)
- `InfraActualStateAdapter` iterating desired nodes, delegating to backend
- `InfraFaultPolicy` with default rules
- `InfraEventSource` with hot event stream (`emit()`/`emitDrift()`) for drift event injection; periodic polling and K8s informer-based drift detection deferred to ReconciliationLoop
- Tests validating the lifecycle: declare → compile → provision → read state → detect drift (via manual event injection) → fault policy evaluation

### 15.2 Next Phase

- `TerraformBackend` (generative + wrapping)
- `AnsibleBackend` (generative + wrapping)
- `LlmProvisioner` strategy with generate-cache-trust lifecycle
- Cloud resource types (ComputeInstanceSpec, DatabaseClusterSpec)
- Remote execution support (Terraform Cloud API, AWX API)
- ResourceProvisioner Preferences override for strategy selection

### 15.3 Deferred

- `GenericResourceSpec` pass-through for arbitrary Terraform resources
- Multi-tenant reconciliation partitioning
- Scale limits on auto-remediation
- RBAC on provisioning actions
- Intentional drift detection and acceptance (operator UX design)

---

## 16. Open Questions (to resolve during implementation)

1. **Standalone state persistence** — where does the StandaloneBackend store its state? Options: casehub-ledger entries, a dedicated Flyway-managed table, or an in-memory store for the proof-of-concept.

2. **Terraform workspace isolation** — in generative mode, each node maps to one Terraform workspace. How is the workspace directory structure managed? Temp directories, a configured workspace root, or something else?

3. **Idempotency verification** — how do we test that `provision()` is idempotent across all backends? Contract tests in casehub-ops-testing?

4. **K8s informer lifecycle** — for standalone drift detection, K8s informers need long-lived connections. How are they managed in the Quarkus lifecycle? `@Startup` bean?

5. **GoalCompiler structured error reporting** — `GoalCompiler.compile()` returns `DesiredStateGraph` with no error channel. The infra compiler throws `IllegalArgumentException` for validation errors (missing fields, incompatible backends). This reports one error at a time. For a production compiler that should report all validation errors at once, the SPI needs a result type (`CompilationResult<G>` with Success and ValidationErrors variants). Consider proposing to casehub-desiredstate. Adequate for PoC.

6. **Sealed hierarchy scaling strategy** — `InfraNodeSpec` permits 9 types. The design intent is **small hierarchy**: the permits list stays small for types CaseHub validates deeply (K8s core + a few cloud primitives). Everything else uses `GenericResourceSpec`. The hierarchy represents "types CaseHub knows deeply enough to validate at compile time." New well-known types are added only when CaseHub needs compile-time validation for them — not for ecosystem coverage (that's the Terraform backend's job).

7. **Optional fields on resource specs** — `ResourceRequirements` is required on `K8sDeploymentSpec` but optional in Kubernetes. `BackupConfig` is required on `DatabaseClusterSpec` but not all databases have backup configured. Consider making these nullable (with null checks at usage sites) or wrapping in `Optional`. The current implementation requires them; the spec's intent is that the compiler should accept K8s-idiomatic configurations.

8. **TerraformWorkspaceSpec state config redundancy** — In wrapping mode, the operator's `.tf` files already contain a `backend {}` block. Requiring re-declaration in CaseHub YAML is redundant. The `TerraformBackend` could read backend config directly from the `.tf` files. For the PoC, explicit config is simpler. For wrapping mode's value proposition, this redundancy should be eliminated.

---

*This spec was produced through brainstorming and revised through seven code review rounds against IoT ARC42STORIES, platform reactive protocols, engine SPI contracts, DeviceProvider/CdiDeviceRegistry patterns, and post-implementation verification. PoC implementation complete — see implementation plan at `docs/superpowers/plans/2026-06-13-infra-poc-implementation.md`.*
