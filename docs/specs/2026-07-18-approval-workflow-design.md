# Approval Workflow for Provisioning Operations — Design Spec

**Issue:** #43
**Date:** 2026-07-18
**Status:** Approved

## Problem

The ops console has approval infrastructure at the SPI level (desiredstate-api defines
`PendingApprovalHandler`, `ApprovalCheckResult`, `PlanApproval`) and domain evaluators
(`DeploymentApprovalEvaluator` classifies risk and returns `PendingApproval` for high-risk
nodes). But three gaps prevent approval from working end-to-end:

1. **In-memory approval state** — `OpsPendingApprovalHandler` uses `ConcurrentHashMap`,
   losing all pending approvals on restart. The platform provides
   `WorkItemPendingApprovalHandler` (in `casehub-desiredstate-work`) which creates
   persistent WorkItems via casehub-work. The ops handler overrides it via CDI priority.

2. **No K8s approval gates** — `KubernetesNodeProvisioner` always returns `Success` or
   `Failed`. High-risk K8s operations (namespace deletion, production deployments) bypass
   approval entirely.

3. **Stubbed REST API** — `ApprovalResource` has four endpoints, all returning empty stubs.
   No way to list, view, approve, or reject pending approvals.

## Design

### Principle: Use the Platform

casehub-work exists to manage human tasks. Approval is a human task. The runtime's
`WorkItemPendingApprovalHandler` already creates WorkItems with `desiredstate-approval`
type, uses `callerRef` for idempotent polling, maps all terminal statuses
(COMPLETED → Approved, REJECTED/CANCELLED/EXPIRED/FAULTED/ESCALATED → Rejected,
OBSOLETE → None), and calls `obsoleteByCallerRef` for cleanup.

The in-memory `OpsPendingApprovalHandler` was scaffolding. Replace it.

### Change 1: Add `casehub-desiredstate-work` dependency

Add to `app/pom.xml`:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-work</artifactId>
</dependency>
```

This Jandex library activates by classpath presence, providing
`WorkItemPendingApprovalHandler` as an `@ApplicationScoped` bean that displaces the
runtime's `NoOpPendingApprovalHandler` (`@DefaultBean`).

### Change 2: Delete `OpsPendingApprovalHandler`

Remove from `api/`:
- `OpsPendingApprovalHandler.java`
- `OpsPendingApprovalHandlerTest.java`

With `casehub-desiredstate-work` on the classpath, `WorkItemPendingApprovalHandler`
becomes the sole `PendingApprovalHandler` implementation. No CDI ambiguity — the ops
handler is gone.

**Atomicity:** Changes 1 and 2 must land in the same commit. If Change 2 lands
first without Change 1, the runtime's `NoOpPendingApprovalHandler @DefaultBean`
becomes the sole handler and returns `StepOutcome.Failed` from `recordPending()` —
every approval-gated provisioner would report failure.

### Change 3: Create `K8sApprovalEvaluator`

New class at `app/src/main/java/io/casehub/ops/app/k8s/K8sApprovalEvaluator.java`.
`@ApplicationScoped implements ApprovalEvaluator`. Injected into
`KubernetesNodeProvisioner` via `@Inject` — same pattern as `DeploymentNodeProvisioner`
injecting `DeploymentApprovalEvaluator`. The app module does not load domain modules
(ARC42STORIES §2 single-domain CDI constraint), so there is no CDI ambiguity — the K8s
evaluator is the only `ApprovalEvaluator` on the classpath.

Classifies by `NodeType` × `StepAction`:

| NodeType | PROVISION | DEPROVISION |
|----------|-----------|-------------|
| K8S_NAMESPACE | LOW | CRITICAL |
| K8S_DEPLOYMENT | MEDIUM | HIGH |
| K8S_SERVICE | LOW | MEDIUM |
| K8S_INGRESS | LOW | MEDIUM |
| K8S_CONFIGMAP | LOW | MEDIUM |

Uses `ApprovalThresholds(HIGH)` — HIGH and CRITICAL require approval.

Generates a human-readable summary for each operation (same pattern as
`DeploymentApprovalEvaluator.generateSummary()`).

### Change 4: Add approval flow to `KubernetesNodeProvisioner`

Inject `ApprovalEvaluator` and `PlanStore`. Same pattern as
`DeploymentNodeProvisioner`:

**provision():**
1. If `context.hasApproval()` → re-entry path (verify plan exists, spec unchanged)
2. Else → `evaluator.evaluate(node, action, tenancyId)`
3. If `RequiresApproval` → `planStore.store(plan)`, return `PendingApproval(nodeId, ref)`
4. If `AutoApproved` → proceed with K8s operation

**Re-entry (approved):**
1. `planStore.retrieve(planReference)` — if empty, re-evaluate (plan lost on restart)
2. If plan exists but `originalSpec` differs from current spec → stale, remove plan,
   re-evaluate (may issue new `PendingApproval`)
3. If plan valid → proceed with K8s operation, clean up plan

**deprovision():** — same pattern.

### Change 5: Implement `ApprovalResource`

Replace stubs with real implementations. Inject `WorkItemService` (from casehub-work
runtime, already on app classpath), `PlanStore`, and `KubernetesEventSource`.

#### Authentication and Authorization

Same pattern as `ClusterResource`:
- **tenancyId** — extracted from `X-Tenancy-ID` header by `TenancyFilter`, read via
  `@Context ContainerRequestContext ctx` and `ctx.getProperty(TenancyFilter.TENANCY_PROPERTY)`.
- **actorId** — provided in the request body (`ApproveRequest.actorId()`,
  `RejectRequest.actorId()`). When Quarkus Security is enabled, this will migrate
  to `@Context SecurityContext` / JWT `sub` claim.
- **Tenancy isolation** — `POST .../approve` and `POST .../reject` must validate
  that the target WorkItem belongs to the caller's tenancy. Load the WorkItem via
  `workItemService.findById(id)`, verify `workItem.tenancyId` matches the caller's
  tenancyId from TenancyFilter. Return 403 Forbidden if they don't match. Without
  this, a caller in tenancy A who obtains a WorkItem UUID from tenancy B could
  approve or reject it — `completeFromSystem`/`rejectFromSystem` do not validate
  tenancy internally.
- **Authorization** — any authenticated caller within the tenancy. Fine-grained
  approval roles (e.g., only ops-admin can approve CRITICAL operations) deferred
  to follow-up (#TODO).

Request body DTOs:

```java
record ApproveRequest(String actorId) {}
record RejectRequest(String actorId, String reason) {}
```

**`GET /api/approvals?tenancyId={tenancyId}`**

```java
var items = workItemService.scan(WorkItemQuery.builder()
        .type("desiredstate-approval")
        .tenancyId(tenancyId)
        .build());
```

For each WorkItem, read `payload` (= planReference) → `planStore.retrieve(ref)` →
return combined DTO:

```java
record ApprovalView(
    UUID workItemId,
    String nodeId,
    String action,
    RiskClassification risk,
    String summary,
    String cluster,       // from InfraDesiredNodeSpec.backendId()
    String namespace,     // from K8s resource spec (e.g. K8sNamespaceSpec.name())
    String status,        // from WorkItem
    String assigneeId,    // from WorkItem
    Instant createdAt     // from WorkItem
) {}
```

Plan enrichment: when the plan is available, extract `cluster` from
`InfraDesiredNodeSpec.backendId()` and `namespace` from the underlying K8s resource
spec. These give the approver sufficient context ("Delete namespace `prod-billing`
on cluster `kubernetes:ops-prod`").

If the plan is not in the store (lost on restart), return a degraded view with
WorkItem metadata only — `title` (set by `WorkItemPendingApprovalHandler` to e.g.
"Approve deprovision: node-id"), `status`, `assigneeId`, `createdAt`. No attempt to
parse `callerRef` — that format is an implementation detail of
`WorkItemPendingApprovalHandler` and coupling to it would be fragile.

**`GET /api/approvals/{id}`** — `workItemService.findById(id)`, tenancy check
(403 on mismatch), then plan enrichment.

**`POST /api/approvals/{id}/approve`** — receives `ApproveRequest` body and
`@Context ContainerRequestContext ctx`.
1. Load WorkItem: `workItemService.findById(id)` → 404 if absent.
2. Tenancy check: verify `workItem.tenancyId` matches caller's tenancyId → 403 if
   mismatch.
3. Transition: `workItemService.completeFromSystem(id, request.actorId(), "approve")`.
   `completeFromSystem` works from any non-terminal status, skipping the
   claim→start→complete lifecycle that doesn't apply to approval WorkItems.
4. Trigger immediate reconciliation: retrieve the plan from `planStore` via the
   WorkItem's `payload` field, extract the `nodeId`, and emit
   `new StateEvent(nodeId, NodeStatus.DRIFTED, "approval completed")` via
   `KubernetesEventSource.emit()`. This triggers the reconciliation loop immediately
   rather than waiting up to 5 minutes for the next `resyncInterval()` cycle. If the
   plan is not available, reconciliation falls back to the next polling cycle.

**`POST /api/approvals/{id}/reject`** — receives `RejectRequest` body and
`@Context ContainerRequestContext ctx`.
1. Load WorkItem: `workItemService.findById(id)` → 404 if absent.
2. Tenancy check: verify `workItem.tenancyId` matches caller's tenancyId → 403 if
   mismatch.
3. Transition: `workItemService.rejectFromSystem(id, request.actorId(), request.reason())`.
Next cycle: `check()` sees REJECTED → returns `ApprovalCheckResult.Rejected` → executor
calls `acknowledgeRejection()` → `obsoleteByCallerRef()` cleans up.

### Change 6: Update integration test

`ApprovalLifecycleIntegrationTest` currently uses `OpsPendingApprovalHandler` directly.
Replace with `MockPendingApprovalHandler` (from casehub-desiredstate-testing, already a
test dependency) for unit-level provisioner tests.

Add a new integration test that wires `WorkItemPendingApprovalHandler` with a test
implementation of `io.casehub.work.api.spi.WorkItemCreator` (SPI from casehub-work-api)
to verify the full lifecycle: provision → PendingApproval → WorkItem created → approve →
re-entry → Success. Pattern: `InMemoryWorkItemCreator` from
`WorkItemPendingApprovalHandlerTest` in casehub-desiredstate-work.

## Data Flow

```
Provisioner                    Runtime                        casehub-work
    │                             │                               │
    │ evaluate(node, action)      │                               │
    │──► ApprovalEvaluator        │                               │
    │◄── RequiresApproval(plan)   │                               │
    │                             │                               │
    │ planStore.store(plan)       │                               │
    │ return PendingApproval      │                               │
    │────────────────────────────►│                               │
    │                             │ recordPending(node, ref)      │
    │                             │──────────────────────────────►│
    │                             │  WorkItem created (OPEN)      │
    │                             │◄──────────────────────────────│
    │                             │                               │
    │                             │  ... reconciliation cycles ...│
    │                             │                               │
    │                             │ check(node)                   │
    │                             │──────────────────────────────►│
    │                             │  findByCallerRef → COMPLETED  │
    │                             │◄──────────────────────────────│
    │                             │ return Approved(planApproval)  │
    │◄────────────────────────────│                               │
    │                             │                               │
    │ provision(node, ctx+approval)                               │
    │ verify plan, execute, cleanup                               │
```

## Garden Context

- **GE-20260629-45f4be** — REJECTED WorkItems block `callerRef` permanently in polling
  loops. `WorkItemPendingApprovalHandler` handles this: `acknowledgeRejection()` calls
  `obsoleteByCallerRef()`, clearing the callerRef for future use.

- **GE-20260629-db82b4** — `WorkItemService.reject()` reason goes to audit events only,
  not stored on `WorkItem.resolution`. The REST reject endpoint should document this:
  the rejection reason is available in the audit trail, not on the WorkItem itself.

## Follow-up (out of scope)

Each deferred item is captured as a GitHub issue on `casehubio/casehub-ops`:

- **Persistent PlanStore** (issue TBD) — `InMemoryPlanStore` loses plan data on
  restart. The provisioner handles this gracefully (re-evaluates), but it means
  re-approval after restart. A JPA-backed `PlanStore` would fix this.

- **Approval notifications** (issue TBD) — WorkItems could trigger notifications
  (Slack, email) via casehub-work's notification infrastructure. Not needed for Phase 3.

- **Configurable risk thresholds** (issue TBD) — currently hardcoded per evaluator.
  Could be made configurable via application properties.

- **Context-aware risk classification** (issue TBD) — risk classification is
  currently static (`NodeType × StepAction`). The evaluator receives the full
  `DesiredNode` with `InfraDesiredNodeSpec.backendId()` (encoding the cluster, e.g.
  `"kubernetes:ops-prod"`) and namespace. Future work: environment-aware risk
  (prod namespace deletion = CRITICAL, dev = LOW).

- **Fine-grained approval authorization** (issue TBD) — initial implementation
  allows any authenticated caller to approve. Future: role-based approval gates
  (e.g. only ops-admin for CRITICAL operations).
