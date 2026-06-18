# Compliance Posture Domain — Design Spec

**Issue:** casehubio/casehub-ops#3
**Date:** 2026-06-18
**Status:** Draft (revision 4 — post-review)

---

## Overview

Continuous compliance posture management as a desired-state control plane. Implements the `casehub-desiredstate` SPIs for the compliance domain — GoalCompiler, ActualStateAdapter, NodeProvisioner, FaultPolicy — with framework-aware posture scoring on top.

Six regulatory frameworks: SOC2-TypeII, GDPR, EU AI Act Art.12, DORA, NIS2, ISO27001.
Six control types: LogRetention, EncryptionAtRest, AccessReview, IncidentResponse, DataProcessing, AIRiskAssessment.

**Differentiator:** Vanta/Drata/Secureframe do point-in-time audit prep. This module provides continuous compliance posture via desired-state reconciliation — controls are declared, evidence is collected into a tamper-evident ledger, drift is detected when evidence goes stale, and the reconciliation loop re-collects. The `CompliancePostureService` answers "are we SOC2 compliant right now?" as a single method call.

**Single-domain assumption:** This module provides `@ApplicationScoped` implementations of `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, and `EventSource`. It cannot coexist on the same classpath as another desired-state domain module (e.g., deployment) without CDI ambiguity. Each domain module is a standalone integration — a compliance application deploys with the compliance module, not with deployment topology.

---

## Prerequisites

### TransitionPlanner DRIFTED fix (casehub-desiredstate companion issue)

The compliance module depends on DRIFTED → re-provision for continuous evidence collection. When evidence goes stale or a control fails, the `ActualStateAdapter` returns `NodeStatus.DRIFTED`, and the reconciliation loop must re-provision the node to re-collect evidence.

The current `TransitionPlanner.plan()` only handles:
- `ABSENT`/`UNKNOWN` → PROVISION
- `PRESENT` not in desired → DEPROVISION

DRIFTED has no code path — the planner silently ignores it. The `ReconciliationLoop.detectDrift()` correctly identifies drifted nodes and emits `FaultEvent(NODE_DEGRADED)`, but fault policy mutations cannot fix this: `RemoveNode` doesn't trigger deprovision for DRIFTED nodes (planner checks `status == PRESENT` specifically), and `UpdateNode` only modifies the desired graph without changing actual state.

**The correct fix is a one-line change in `TransitionPlanner`:** treat DRIFTED like ABSENT (add to `toAdd`). All compliance provisioners are idempotent (collect evidence, write ledger entry), so re-provisioning a drifted node is safe.

Without this fix, evidence collection happens on first run (ABSENT → PROVISION) but stale evidence never triggers re-collection — degrading the module from continuous compliance to one-shot audit.

This is the same prerequisite documented in the [deployment app-level topology spec](2026-06-17-deployment-app-level-topology-design.md).

---

## Design Decisions

### D1 — Two-dimensional control model (A + B)

Each compliance control has two dimensions:

- **A — Configuration assertion:** the control declares what should be true (e.g., "encryption must be AES-256"). An `EvidenceCollector` SPI checks whether it IS true by querying the actual system.
- **B — Evidence-based posture:** the control declares how recently evidence must have been collected (`evidenceMaxAgeDays`). Drift detection queries the ledger for the latest evidence and compares against this threshold.

Policy-document tracking (dimension C) is deferred to a follow-up.

### D2 — Control-centric, not framework-centric

A control is a single node that maps to multiple frameworks via `FrameworkMapping`. `EncryptionAtRestControl` satisfies SOC2 CC6.1, GDPR Art.32, DORA Art.9, NIS2 Art.21, and ISO27001 A.8.24 simultaneously. Framework compliance is a query/projection over the control graph, not a separate graph per framework.

### D3 — Generic control spec, not sealed hierarchy

One `ComplianceControlSpec` record with a `controlType` discriminator and `Map<String, Object> properties` for type-specific configuration. Compliance controls are structurally uniform — all have framework mappings, evidence requirements, and drift thresholds. The type-specific parts (`cipher: AES-256`, `retentionDays: 365`) are configuration consumed by EvidenceCollectors, not structural differences in the spec.

This differs from deployment's sealed `DeploymentNodeSpec` because deployment's four node types are structurally different (agents have capabilities, channels have semantics, trust policies have thresholds). Compliance controls differ in configuration, not structure. A sealed hierarchy would add ~6 nearly-identical record types with 1-3 unique fields each, plus switch expressions that do nothing the `controlType` discriminator doesn't already do.

Adding a new control type requires a new `EvidenceCollector` implementation and YAML schema — not a new sealed variant cascading through switch expressions.

### D4 — Evidence as tamper-evident ledger entries

Evidence is stored as `ComplianceLedgerEntry extends LedgerEntry` — JPA JOINED inheritance, consumer-owned V2000 migration. This gives tamper-evident, timestamped, tenant-scoped compliance evidence for free. The ledger is the natural home — it already handles Merkle verification and time-ordered queries.

### D5 — Framework registry and posture service are core

The `ComplianceFrameworkRegistry` and `CompliancePostureService` are core infrastructure, not optional nice-to-haves. Without them this is just another desired-state domain. The posture service is what answers "show me our SOC2 compliance status" — the differentiator from point-in-time audit tools.

### D6 — Centralized evidence status interpretation

Evidence freshness/outcome interpretation is a single concern shared by two consumers: `ComplianceActualStateAdapter` (needs `NodeStatus`) and `CompliancePostureService` (needs `ControlEvidenceStatus`). Rather than each component independently querying the ledger and interpreting results, `ComplianceEvidenceService` computes a `ControlEvidenceStatus` record that both consume. Single source of truth for "is this control passing, failing, stale, or absent?"

---

## Core Types (`api/` module)

Package: `io.casehub.ops.api.compliance`

### ComplianceControlSpec

The generic control specification. Implements `NodeSpec` from `casehub-desiredstate-api`.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceControlSpec(
    String controlId,
    String controlType,
    String title,
    String description,
    List<FrameworkMapping> frameworks,
    int evidenceMaxAgeDays,
    boolean requiresHumanReview,
    Map<String, Object> properties
) implements NodeSpec {

    public ComplianceControlSpec {
        if (controlId == null || controlId.isBlank()) {
            throw new IllegalArgumentException("controlId is required");
        }
        if (controlType == null || controlType.isBlank()) {
            throw new IllegalArgumentException("controlType is required");
        }
        if (evidenceMaxAgeDays <= 0) {
            throw new IllegalArgumentException("evidenceMaxAgeDays must be positive");
        }
        frameworks = frameworks != null ? List.copyOf(frameworks) : List.of();
        properties = properties != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(properties))
                : Map.of();
    }
}
```

Fields:
- `controlId` — unique identifier, e.g. `"encryption-at-rest"`. Used as the `NodeId` value.
- `controlType` — discriminator for EvidenceCollector dispatch, e.g. `"ENCRYPTION_AT_REST"`.
- `title` — human-readable name.
- `description` — what the control requires.
- `frameworks` — list of `FrameworkMapping` linking this control to regulatory requirements.
- `evidenceMaxAgeDays` — B-drift threshold. Evidence older than this many days triggers DRIFTED status.
- `requiresHumanReview` — controls like AccessReview need human sign-off. Maps to `DesiredNode.requiresHuman` which generates casehub-work WorkItems.
- `properties` — type-specific configuration (cipher suite, retention period, review cadence, etc.). Uses `Collections.unmodifiableMap()` instead of `Map.copyOf()` because YAML-parsed maps may contain null values (`someField: null`, `someField: ~`).

### FrameworkMapping

Links a control to a specific framework requirement.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record FrameworkMapping(
    String framework,
    String requirement
) {
    public FrameworkMapping {
        if (framework == null || framework.isBlank()) {
            throw new IllegalArgumentException("framework is required");
        }
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("requirement is required");
        }
    }
}
```

Framework values: `"SOC2"`, `"GDPR"`, `"EU_AI_ACT"`, `"DORA"`, `"NIS2"`, `"ISO27001"`.

### ComplianceGoalEntry

Wraps a control spec with dependency support.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceGoalEntry(
    ComplianceControlSpec spec,
    List<String> dependsOn
) {
    public ComplianceGoalEntry {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
```

`dependsOn` references other `controlId` values within the same goal declaration. The goal compiler wires them as `Dependency` edges. The runtime's `TransitionPlanner` handles ordering.

### ComplianceGoals

Top-level goal declaration parsed from YAML.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceGoals(
    List<ComplianceGoalEntry> controls
) {
    public ComplianceGoals {
        controls = controls != null ? List.copyOf(controls) : List.of();
    }
}
```

Flat structure — a single list of controls. Unlike `DeploymentGoals` which has separate lists per node type, compliance uses a single generic spec with a `controlType` discriminator.

### EvidenceCollector (SPI)

Pluggable interface for dimension A — configuration assertion checking.

```java
public interface EvidenceCollector {
    String controlType();
    EvidenceResult collect(ComplianceControlSpec spec, String tenancyId);
}
```

Discovered via CDI `Instance<EvidenceCollector>`, dispatched by `controlType()`. External integrations (cloud APIs, identity providers, monitoring systems) implement this SPI in their own modules.

### EvidenceResult

Sealed result of evidence collection.

```java
public sealed interface EvidenceResult {
    String detail();
    record Pass(String detail) implements EvidenceResult {}
    record Fail(String detail) implements EvidenceResult {}
    record Unavailable(String detail) implements EvidenceResult {}
}
```

- `Pass` — the control's configuration assertion is satisfied.
- `Fail` — the assertion check ran but the control is not met.
- `Unavailable` — the check could not run (system unreachable, credentials missing, etc.).

All three variants carry `detail()` — a human-readable description of what happened. The variant type (`Pass`/`Fail`/`Unavailable`) provides the semantic distinction; uniform naming enables `result.detail()` without pattern matching.

### EvidenceOutcome

Persistable enum mirroring `EvidenceResult` for the ledger entry.

```java
public enum EvidenceOutcome {
    PASS,
    FAIL,
    UNAVAILABLE
}
```

---

## Compliance Module (`compliance/`)

Package: `io.casehub.ops.compliance`

### ComplianceGoalCompiler

Implements `GoalCompiler<ComplianceGoals>`. Transforms the YAML goal declaration into a `DesiredStateGraph`.

Each `ComplianceGoalEntry` becomes a `DesiredNode` with:
- `NodeId` from `spec.controlId()`
- `NodeType` from `spec.controlType()`
- `NodeSpec` is the `ComplianceControlSpec` itself
- `requiresHuman` from `spec.requiresHumanReview()`

Dependencies from `dependsOn` become `Dependency` edges — `dependsOn` values reference other `controlId` values. The runtime's `TransitionPlanner` handles pruning-first ordering.

### ComplianceActualStateAdapter

Implements `ActualStateAdapter`. Two-layer drift detection using `ControlEvidenceStatus` from `ComplianceEvidenceService`.

**Layer 1 — Evidence status:** queries `ComplianceEvidenceService.evidenceStatus()` for each control within the tenant. Returns the `derivedNodeStatus` from the `ControlEvidenceStatus` record:
- No entry exists → `NodeStatus.ABSENT`
- Latest entry stale (older than `evidenceMaxAgeDays`) → `NodeStatus.DRIFTED`
- Latest entry fresh with `PASS` outcome → `NodeStatus.PRESENT`
- Latest entry fresh with `FAIL` outcome → `NodeStatus.DRIFTED`
- Latest entry fresh with `UNAVAILABLE` outcome → `NodeStatus.DRIFTED`

**Layer 2 — Spec hash (declaration drift):** for nodes that are `PRESENT` from Layer 1, checks `ComplianceSpecHashStore.hasDrifted()` — did the control's declaration change since last provision? If yes → `NodeStatus.DRIFTED` (forces re-provisioning with updated spec).

No `NodeDriftChecker` SPI — unlike deployment, compliance doesn't have external foundation modules overriding drift checking. The `EvidenceCollector` SPI is the extension point for external integrations, but that operates at provision/evidence-collection time, not at drift-check time. Deployment's `NodeDriftChecker` exists because it queries external registries (AgentRegistry, ChannelLookup) for structural presence. Compliance drift is temporal (evidence staleness) and outcome-based (PASS/FAIL/UNAVAILABLE), both derivable from the ledger.

TenancyId: field on the adapter, defaulting to `"default"`. Matches the deployment module's pattern. Test constructors accept a custom tenancyId.

```java
@ApplicationScoped
public class ComplianceActualStateAdapter implements ActualStateAdapter {

    private final ComplianceEvidenceService evidenceService;
    private final ComplianceSpecHashStore specHashStore;
    private final String tenancyId;

    @Inject
    public ComplianceActualStateAdapter(
            ComplianceEvidenceService evidenceService,
            ComplianceSpecHashStore specHashStore) {
        this.evidenceService = evidenceService;
        this.specHashStore = specHashStore;
        this.tenancyId = "default";
    }

    // Test constructor
    ComplianceActualStateAdapter(
            ComplianceEvidenceService evidenceService,
            ComplianceSpecHashStore specHashStore,
            String tenancyId) {
        this.evidenceService = evidenceService;
        this.specHashStore = specHashStore;
        this.tenancyId = tenancyId;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var node : desired.nodes().values()) {
            statuses.put(node.id(), checkNode(node));
        }
        return new ActualState(statuses);
    }

    private NodeStatus checkNode(DesiredNode node) {
        ComplianceControlSpec spec = (ComplianceControlSpec) node.spec();
        ControlEvidenceStatus status = evidenceService.evidenceStatus(spec, tenancyId);

        if (status.derivedNodeStatus() == NodeStatus.PRESENT
                && specHashStore.hasDrifted(node.id(), node.spec())) {
            return NodeStatus.DRIFTED;
        }
        return status.derivedNodeStatus();
    }
}
```

### ComplianceNodeProvisioner

Implements `NodeProvisioner`. Provision means:
1. Invoke the `EvidenceCollector` for this control type via `ComplianceEvidenceService`
2. Write a `ComplianceLedgerEntry` with the evidence result
3. Register the control with the `ComplianceFrameworkRegistry`
4. Record the spec hash in `ComplianceSpecHashStore`

Deprovision means:
1. Deregister the control from the `ComplianceFrameworkRegistry`
2. Remove the spec hash from `ComplianceSpecHashStore`

`EvidenceCollector` dispatch: CDI `Instance<EvidenceCollector>` collected into a `Map<String, EvidenceCollector>` keyed by `controlType()`. Same pattern as `NodeDriftChecker` dispatch in `DeploymentActualStateAdapter`.

### ComplianceFaultPolicy

Implements `FaultPolicy`. Returns `List.of()` — no-op. This is intentional, not a stub.

The reconciliation loop emits `FaultEvent(NODE_DEGRADED)` for drifted nodes and asks the fault policy for graph mutations. But fault policy mutations cannot fix DRIFTED: `RemoveNode` doesn't trigger deprovision (planner checks `status == PRESENT`), and `UpdateNode` only modifies the desired graph. The correct fix is in the TransitionPlanner (see Prerequisites).

Once the TransitionPlanner treats DRIFTED as re-provisionable, the fault policy remains no-op because the planner handles it directly. The runtime's default retry behaviour covers the remaining cases:
- `PROVISION_FAILED` on a non-human control → next reconciliation cycle retries
- `PROVISION_FAILED` on a `requiresHumanReview` control → WorkItem is the retry mechanism
- `NODE_DEGRADED` (evidence went stale) → TransitionPlanner re-provisions (pending prerequisite)

Richer fault escalation (e.g., "evidence failed N times → create WorkItem for manual review") is a follow-up.

### ComplianceEventSource

Implements `EventSource`. Required by `ReconciliationLoop` as a CDI constructor dependency — without this bean, CDI wiring fails at startup.

```java
@ApplicationScoped
public class ComplianceEventSource implements EventSource {

    private final Multi<StateEvent> stream;
    private volatile MultiEmitter<? super StateEvent> emitter;

    public ComplianceEventSource() {
        this.stream = Multi.createFrom()
                .<StateEvent>emitter(e -> this.emitter = e, BackPressureStrategy.BUFFER)
                .broadcast().toAllSubscribers();
    }

    @Override
    public Multi<StateEvent> stream() {
        return stream;
    }

    public void emit(StateEvent event) {
        var e = this.emitter;
        if (e != null) {
            e.emit(event);
        }
    }
}
```

Same hot-stream pattern as `DeploymentEventSource`. The reconciliation loop's periodic resync (default 5 min) handles time-based re-evaluation. The event source provides a channel for external drift signals — e.g., a webhook from a cloud provider reporting an encryption policy change could emit a drift event to trigger immediate re-evaluation rather than waiting for the next resync.

### ComplianceGoalLoader

YAML loading. Structurally identical to `DeploymentGoalLoader`. Reads `casehub-compliance.yaml` from classpath-first, filesystem-fallback. Supports single-file loading, directory-based loading (all YAML files merged), and explicit fragment merging.

### ComplianceSpecHashStore

In-memory `ConcurrentHashMap<NodeId, Integer>`. Records spec hashes at provision time, checks for spec-level drift. Same pattern as deployment's `SpecHashStore` — kept separate because each module's store is an internal implementation detail operating on different node types. The structural similarity is coincidence, not a shared concern.

### ControlEvidenceStatus

Per-control evidence status. Internal to the compliance module — consumed only by `ComplianceActualStateAdapter` and `CompliancePostureService`. Lives in the compliance module, not the api module, because it references `NodeStatus` (a runtime concept) and is not part of the external contract.

```java
public record ControlEvidenceStatus(
    String controlId,
    String controlType,
    EvidenceOutcome latestOutcome,
    Instant latestEvidenceAt,
    int evidenceMaxAgeDays,
    boolean stale,
    NodeStatus derivedNodeStatus
) {
    public static ControlEvidenceStatus absent(String controlId, String controlType, int evidenceMaxAgeDays) {
        return new ControlEvidenceStatus(controlId, controlType, null, null, evidenceMaxAgeDays, false, NodeStatus.ABSENT);
    }
}
```

Derivation rules (implemented once in `ComplianceEvidenceService.evidenceStatus()`):
- No evidence exists → `ABSENT` (stale=false — "no evidence" is not "stale evidence")
- Evidence older than `evidenceMaxAgeDays` → `DRIFTED` (stale=true), regardless of outcome
- Fresh evidence with `PASS` → `PRESENT` (stale=false)
- Fresh evidence with `FAIL` → `DRIFTED` (stale=false)
- Fresh evidence with `UNAVAILABLE` → `DRIFTED` (stale=false) — conservative: if we can't verify a control, treat it as drifted and re-check on next cycle

### ComplianceEvidenceService

Orchestrates evidence collection, ledger writes, and evidence status interpretation.

```java
@ApplicationScoped
public class ComplianceEvidenceService {

    private final Map<String, EvidenceCollector> collectors;
    private final LedgerEntryRepository ledgerRepository;  // save() + findLatestBySubjectId()
    private final EntityManager entityManager;              // type-safe named queries for history

    EvidenceOutcome collectAndRecord(ComplianceControlSpec spec, String tenancyId);
    Optional<ComplianceLedgerEntry> latestEvidence(String controlId, String tenancyId);
    ControlEvidenceStatus evidenceStatus(ComplianceControlSpec spec, String tenancyId);
}
```

`collectAndRecord()`:
1. Finds the `EvidenceCollector` for `spec.controlType()`
2. Invokes `collector.collect(spec, tenancyId)`
3. Maps `EvidenceResult` to `EvidenceOutcome`
4. Creates a `ComplianceLedgerEntry` with base fields populated (see below)
5. Persists via `LedgerEntryRepository.save(entry, tenancyId)`
6. Returns the outcome for the provisioner

**Base field population** — follows the `LedgerErasureService.buildReceipt()` pattern:

```java
ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
// Deterministic UUID — each control forms its own Merkle chain
entry.subjectId = UUID.nameUUIDFromBytes(
    ("compliance:" + spec.controlId()).getBytes(StandardCharsets.UTF_8));
entry.entryType = LedgerEntryType.EVENT;
entry.actorType = ActorType.SYSTEM;
entry.actorId = "system:compliance-evidence";
entry.actorRole = "EvidenceCollector";
entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
// Domain fields
entry.controlId = spec.controlId();
entry.controlType = spec.controlType();
entry.outcome = outcome;
entry.detail = result.detail();
entry.collectorId = collector.controlType();
```

Key decisions:
- `subjectId` uses `UUID.nameUUIDFromBytes()` with a `"compliance:"` namespace prefix to avoid collision with other domains' string-to-UUID mappings. Each control's evidence entries form their own Merkle chain (independent sequence numbers, independent hash frontier).
- `entryType = EVENT` — evidence collection is an event ("evidence was collected and the outcome was X"), not a COMMAND (no state change requested) or ATTESTATION (no third-party verification).
- Actor fields: `SYSTEM` / `"system:compliance-evidence"` / `"EvidenceCollector"` for automated collection. For human-reviewed controls (`requiresHumanReview=true`), the evidence entry actor should reflect the human reviewer once the WorkItem is resolved — but that's a follow-up concern tied to casehub-work integration.

**Query strategy for `latestEvidence()`**: With deterministic `subjectId`, the generic `LedgerEntryRepository.findLatestBySubjectId(subjectId, tenancyId)` works — no custom named query required. The subjectId is computed from the controlId at query time using the same derivation. This is simpler and uses the existing repository API. The custom `@NamedQuery` on `ComplianceLedgerEntry` is retained for type-safe queries where the caller needs `ComplianceLedgerEntry` fields directly (e.g., `findByControlId` for history), but `latestEvidence()` delegates to the generic method and casts.

`evidenceStatus()` — the centralized evidence interpretation method:
1. Calls `latestEvidence()` to get the most recent ledger entry
2. Computes `ControlEvidenceStatus` using the derivation rules from D6:
   - No evidence → `ABSENT`
   - Stale evidence (any outcome) → `DRIFTED`
   - Fresh + PASS → `PRESENT`
   - Fresh + FAIL → `DRIFTED`
   - Fresh + UNAVAILABLE → `DRIFTED`
3. Both `ComplianceActualStateAdapter` and `CompliancePostureService` consume this method

---

## Evidence Infrastructure

### ComplianceLedgerEntry

Tamper-evident evidence record. Follows the ledger subclass extension protocol.

```java
@NamedQueries({
    @NamedQuery(
        name = "ComplianceLedgerEntry.findLatestByControlId",
        query = "SELECT e FROM ComplianceLedgerEntry e WHERE e.controlId = :controlId AND e.tenancyId = :tenancyId ORDER BY e.occurredAt DESC"
    ),
    @NamedQuery(
        name = "ComplianceLedgerEntry.findByControlId",
        query = "SELECT e FROM ComplianceLedgerEntry e WHERE e.controlId = :controlId AND e.tenancyId = :tenancyId ORDER BY e.occurredAt ASC"
    )
})
@Entity
@Table(name = "compliance_evidence_entry")
@DiscriminatorValue("COMPLIANCE_EVIDENCE")
public class ComplianceLedgerEntry extends LedgerEntry {

    @Column(name = "control_id", nullable = false)
    public String controlId;

    @Column(name = "control_type", nullable = false)
    public String controlType;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_outcome", nullable = false)
    public EvidenceOutcome outcome;

    @Column(name = "evidence_detail")
    public String detail;

    @Column(name = "collector_id")
    public String collectorId;

    @Override
    protected byte[] domainContentBytes() {
        String content = String.join("|",
            controlId != null ? controlId : "",
            controlType != null ? controlType : "",
            outcome != null ? outcome.name() : "",
            detail != null ? detail : "",
            collectorId != null ? collectorId : ""
        );
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

Queries: `@NamedQuery` definitions on the entity class follow the `ErasureReceiptLedgerEntry` pattern. For latest-evidence lookups, `ComplianceEvidenceService` uses `LedgerEntryRepository.findLatestBySubjectId()` with the deterministic subjectId — the generic method works because each control has a unique, computed subjectId. The named queries (`findLatestByControlId`, `findByControlId`) are retained for type-safe history queries where the caller needs `ComplianceLedgerEntry` fields directly, executed via `EntityManager.createNamedQuery()`.

Flyway migration: `V2000__compliance_evidence_entry.sql` at `classpath:db/compliance/migration`.

### EvidenceCollector Implementations

Six stubbed implementations, one per control type. All `@ApplicationScoped`. Each returns its `controlType()` string for CDI discovery dispatch.

| Control Type | Class | Stub Behaviour |
|---|---|---|
| `LOG_RETENTION` | `LogRetentionEvidenceCollector` | Always PASS — "stub: retention policy present" |
| `ENCRYPTION_AT_REST` | `EncryptionEvidenceCollector` | Always PASS — "stub: AES-256 encryption verified" |
| `ACCESS_REVIEW` | `AccessReviewEvidenceCollector` | Always PASS — "stub: access review completed" |
| `INCIDENT_RESPONSE` | `IncidentResponseEvidenceCollector` | Always PASS — "stub: playbook verified" |
| `DATA_PROCESSING` | `DataProcessingEvidenceCollector` | Always PASS — "stub: processing records complete" |
| `AI_RISK_ASSESSMENT` | `AiRiskAssessmentEvidenceCollector` | Always PASS — "stub: risk assessment current" |

Package: `io.casehub.ops.compliance.collector`

Real implementations would call cloud APIs, identity providers, monitoring systems, etc. They would be contributed by integrating applications or a dedicated compliance connector module — not by this module.

---

## Framework Registry & Posture Service

### ComplianceFrameworkRegistry

Maintains the live mapping of controls to framework requirements. Populated at provision time, cleared at deprovision time. In-memory `ConcurrentHashMap` storage.

```java
@ApplicationScoped
public class ComplianceFrameworkRegistry {

    void register(ComplianceControlSpec spec);
    void deregister(String controlId);

    List<ComplianceControlSpec> controlsForFramework(String framework);
    List<FrameworkMapping> frameworksForControl(String controlId);
    Set<String> registeredFrameworks();
    Optional<ComplianceControlSpec> findControl(String controlId);
}
```

Re-populated on process restart via the next reconciliation cycle (re-provisions all controls).

### CompliancePostureService

Aggregates control statuses into per-framework compliance scores. Uses `ComplianceEvidenceService.evidenceStatus()` for evidence interpretation — same derivation rules as the ActualStateAdapter.

```java
@ApplicationScoped
public class CompliancePostureService {

    FrameworkPosture postureFor(String framework, String tenancyId);
    Map<String, FrameworkPosture> postureForAll(String tenancyId);
}
```

For each control registered in the `ComplianceFrameworkRegistry`, calls `ComplianceEvidenceService.evidenceStatus()` to get the `ControlEvidenceStatus`, then groups by framework mapping. Read-only projection — no state mutation.

### FrameworkPosture

Per-framework compliance posture.

```java
public record FrameworkPosture(
    String framework,
    int totalControls,
    int passingControls,
    int failingControls,
    int unavailableControls,
    int staleControls,
    int missingControls,
    List<ControlStatus> controls
) {
    public double complianceScore() {
        return totalControls == 0 ? 0.0 : (double) passingControls / totalControls;
    }
}
```

Categories:
- `passingControls` — fresh evidence with PASS outcome
- `failingControls` — fresh evidence with FAIL outcome (confirmed compliance gap)
- `unavailableControls` — fresh evidence with UNAVAILABLE outcome (unknown state — connectivity/credential issue, not a confirmed gap)
- `staleControls` — evidence older than `evidenceMaxAgeDays` (any prior outcome)
- `missingControls` — no evidence ever collected

`total = passing + failing + unavailable + stale + missing`. An auditor seeing "2 failing, 1 unavailable" can distinguish confirmed compliance gaps from unverifiable states — different risk conclusions and different remediation paths.

**Note:** `CompliancePostureService` reports only provisioned controls (populated in `ComplianceFrameworkRegistry` at provision time). If a control is declared in YAML but hasn't been provisioned yet (first reconciliation not complete, or provision failed), it won't appear in the posture report. This gives a runtime view, not a declared view. Consistent with how the deployment module works. A "declared posture" mode comparing goal YAML against evidence is a follow-up.

### ControlStatus

Per-control detail within a framework posture.

```java
public record ControlStatus(
    String controlId,
    String controlType,
    String requirement,
    EvidenceOutcome lastOutcome,
    Instant lastEvidenceAt,
    boolean stale
) {}
```

---

## YAML Goal Format

Example `casehub-compliance.yaml`:

```yaml
controls:
  - spec:
      controlId: encryption-at-rest
      controlType: ENCRYPTION_AT_REST
      title: "Encryption at Rest"
      description: "All data stores must use AES-256 encryption at rest"
      frameworks:
        - framework: SOC2
          requirement: CC6.1
        - framework: GDPR
          requirement: Art.32
        - framework: DORA
          requirement: Art.9
        - framework: NIS2
          requirement: Art.21
        - framework: ISO27001
          requirement: A.8.24
      evidenceMaxAgeDays: 30
      requiresHumanReview: false
      properties:
        cipher: AES-256
        scope: all-datastores

  - spec:
      controlId: log-retention-policy
      controlType: LOG_RETENTION
      title: "Log Retention Policy"
      description: "All audit logs must be retained for minimum 1 year"
      frameworks:
        - framework: SOC2
          requirement: CC7.2
        - framework: GDPR
          requirement: Art.30
        - framework: DORA
          requirement: Art.12
        - framework: ISO27001
          requirement: A.8.15
      evidenceMaxAgeDays: 90
      requiresHumanReview: false
      properties:
        retentionDays: 365
        scope: all-audit-logs

  - spec:
      controlId: access-review-quarterly
      controlType: ACCESS_REVIEW
      title: "Quarterly Access Review"
      description: "All user access must be reviewed quarterly"
      frameworks:
        - framework: SOC2
          requirement: CC6.2
        - framework: ISO27001
          requirement: A.5.18
      evidenceMaxAgeDays: 90
      requiresHumanReview: true
      properties:
        cadence: quarterly
        scope: all-users

  - spec:
      controlId: incident-response-playbook
      controlType: INCIDENT_RESPONSE
      title: "Incident Response Playbook"
      description: "Security incident response playbook must be tested annually"
      frameworks:
        - framework: SOC2
          requirement: CC7.4
        - framework: DORA
          requirement: Art.17
        - framework: NIS2
          requirement: Art.21
        - framework: ISO27001
          requirement: A.5.24
      evidenceMaxAgeDays: 365
      requiresHumanReview: true
      properties:
        testCadence: annual
        scope: security-incidents

  - spec:
      controlId: data-processing-records
      controlType: DATA_PROCESSING
      title: "Data Processing Records"
      description: "GDPR Art.30 records of processing activities must be maintained"
      frameworks:
        - framework: GDPR
          requirement: Art.30
        - framework: EU_AI_ACT
          requirement: Art.12
      evidenceMaxAgeDays: 30
      requiresHumanReview: false
      properties:
        scope: all-processing-activities

  - spec:
      controlId: ai-risk-assessment
      controlType: AI_RISK_ASSESSMENT
      title: "AI System Risk Assessment"
      description: "EU AI Act Art.12 risk assessment for all AI systems"
      frameworks:
        - framework: EU_AI_ACT
          requirement: Art.12
        - framework: DORA
          requirement: Art.11
      evidenceMaxAgeDays: 365
      requiresHumanReview: true
      properties:
        riskTier: high
        assessmentScope: all-ai-systems
```

Supports single-file and directory-based loading. Multiple YAML files are merged into one `ComplianceGoals` by `ComplianceGoalLoader`.

---

## Module Dependencies

### `api/` module (casehub-ops-api)

No new dependencies — `ComplianceControlSpec`, `FrameworkMapping`, `ComplianceGoalEntry`, `ComplianceGoals`, `EvidenceCollector`, `EvidenceResult`, `EvidenceOutcome` are pure Java + Jackson annotations (already present).

### `compliance/` module (casehub-ops-compliance)

| Dependency | Scope | Why |
|---|---|---|
| `casehub-ops-api` | compile | `ComplianceControlSpec`, `EvidenceCollector` SPI |
| `casehub-desiredstate-api` | compile | `GoalCompiler`, `NodeProvisioner`, `ActualStateAdapter`, `FaultPolicy`, `EventSource` |
| `casehub-ledger-api` | compile | `LedgerEntry` base class |
| `casehub-ledger` (runtime) | compile | JPA entity registration, Flyway migration discovery |
| `jackson-dataformat-yaml` | compile | YAML goal loading |
| `smallrye-mutiny` | compile | `Multi<StateEvent>` for `ComplianceEventSource` |
| `casehub-desiredstate-testing` | test | `MockNodeProvisioner`, `DefaultDesiredStateGraphFactory` |
| `casehub-ops-testing` | test | Shared test fixtures |

No dependency on `casehub-eidos`, `casehub-qhorus`, or `casehub-engine`.

Jandex indexed — activates by classpath presence, no config required.

Flyway: `V2000__compliance_evidence_entry.sql` at `classpath:db/compliance/migration`. Consumers add `classpath:db/compliance/migration,classpath:db/ledger/migration` to their Flyway locations.

---

## Type Placement Summary

| Location | Types |
|---|---|
| `api/src/main/java/.../compliance/` | `ComplianceControlSpec`, `ComplianceGoals`, `ComplianceGoalEntry`, `FrameworkMapping`, `EvidenceCollector`, `EvidenceResult`, `EvidenceOutcome` |
| `compliance/src/main/java/.../compliance/` | `ComplianceGoalCompiler`, `ComplianceActualStateAdapter`, `ComplianceNodeProvisioner`, `ComplianceFaultPolicy`, `ComplianceEventSource`, `ComplianceGoalLoader`, `ComplianceSpecHashStore`, `ComplianceEvidenceService`, `ControlEvidenceStatus`, `ComplianceLedgerEntry`, `ComplianceFrameworkRegistry`, `CompliancePostureService`, `FrameworkPosture`, `ControlStatus` |
| `compliance/src/main/java/.../compliance/collector/` | `LogRetentionEvidenceCollector`, `EncryptionEvidenceCollector`, `AccessReviewEvidenceCollector`, `IncidentResponseEvidenceCollector`, `DataProcessingEvidenceCollector`, `AiRiskAssessmentEvidenceCollector` |

---

## Protocol Coherence

| Protocol | Status |
|---|---|
| Module tier structure | ✅ Integration tier, Jandex library, classpath activation |
| Ledger subclass extension | ✅ JOINED inheritance, V2000 migration, `domainContentBytes()` |
| Flyway repo-scoped path | ✅ `db/compliance/migration/` |
| Flyway version range | ✅ V2000 (ledger subclass join range) |
| Optional module pattern | ✅ No config required |
| Submodule folder naming | ✅ `compliance/` not `casehub-ops-compliance/` |
| Consumer SPI placement | ✅ `EvidenceCollector` in api module |
| Single-domain deployment | ✅ One domain module per classpath — documented assumption |

---

## Testing

Unit tests per class. Integration test wiring GoalLoader → GoalCompiler → ActualStateAdapter → NodeProvisioner through a full reconciliation cycle. Stubbed `EvidenceCollector` implementations make the loop fully testable without external services.

Key test scenarios:
- Goal compilation: YAML → DesiredStateGraph with correct nodes, types, and dependencies
- Evidence collection: each collector returns expected result, ledger entry is written
- Drift detection: fresh evidence → PRESENT, stale evidence → DRIFTED, no evidence → ABSENT, FAIL outcome → DRIFTED, UNAVAILABLE outcome → DRIFTED
- Spec hash drift: changed spec on PRESENT node → DRIFTED
- Provision/deprovision: registry updated, spec hash recorded/removed, ledger entry written
- Framework posture: correct aggregation of control statuses per framework, compliance score calculation
- Evidence status centralization: `ComplianceEvidenceService.evidenceStatus()` returns consistent results consumed by both ActualStateAdapter and PostureService
- YAML loading: single-file, directory, merge, malformed input handling
- Human review: `requiresHumanReview=true` produces `DesiredNode` with `requiresHuman=true`
- Event source: CDI wiring succeeds, events emitted after subscription are received

---

## PLATFORM.md Updates

Capability Ownership table — add:

| Capability | Owner | Notes |
|---|---|---|
| Continuous compliance posture management | `casehub-ops` (compliance module) | Six frameworks (SOC2, GDPR, EU AI Act, DORA, NIS2, ISO27001), six control types, evidence-based drift detection via `ComplianceLedgerEntry`, per-framework posture scoring via `CompliancePostureService`. `EvidenceCollector` SPI for external integration. Research project. |

Repository Map — update casehub-ops entry to include compliance module description.

Cross-Repo Dependency Map — add:

| Artifact consumed | Consuming repo | Consuming module | Nature |
|---|---|---|---|
| `casehub-ledger-api` | `casehub-ops` | `compliance` | `LedgerEntry` base class for `ComplianceLedgerEntry` |
| `casehub-ledger` (runtime) | `casehub-ops` | `compliance` | JPA entity registration, evidence queries |

---

## Out of Scope

- **Dimension C (policy-document tracking):** deferred to follow-up.
- **Real EvidenceCollector implementations:** stubs only. Real cloud/identity/monitoring integrations are separate modules.
- **REST API for posture queries:** the posture service is injectable Java. A REST surface is a follow-up if needed.
- **Fault escalation beyond no-op:** richer fault policies (e.g., "3 failures → create WorkItem") are a follow-up. Blocked on TransitionPlanner DRIFTED fix for basic drift handling.
- **ComplianceLedgerEntry queries beyond latest-per-control:** historical trend analysis, compliance timeline, etc. are follow-ups.
- **Multi-domain composition:** running compliance alongside another desired-state domain module on the same classpath. Would require CDI qualifiers or a composite adapter pattern. Not needed — each domain is a standalone application.
