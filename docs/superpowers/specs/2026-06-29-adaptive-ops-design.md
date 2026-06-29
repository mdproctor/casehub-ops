# Adaptive Ops — Desired-State Topology Adaptation via RAS Situations

**Date:** 2026-06-29
**Issue:** casehubio/casehub-ops#25, casehubio/casehub-ops#26
**Status:** Design approved

## Problem

casehub-ops-deployment manages agent topology through desired-state reconciliation, but the desired state is static — compiled once from YAML. Real applications need topology that adapts to runtime conditions:

- **Self-healing:** agent dies at 3 AM → re-provision automatically
- **Auto-scaling:** market volatility spikes → scale up risk agents
- **Adaptive security posture:** breach detected → tighten trust policies, add forensics agents

Two CaseHub applications drive the design:

- **casehub-fsitrading** (#25) — multi-agent trading automation, overnight incident response
- **casehub-soc** (#26) — multi-agent cyber incident response, 24/7 operations

## Research Backing

Deep research (26 sources, 109 agents, adversarial verification — 10 confirmed, 15 killed):

- Financial trading multi-agent systems lack desired-state reconciliation, self-healing, and auto-scaling (AgenticTrading, NeurIPS 2025 Workshop — 6-0 verified)
- 47.6% of self-adaptive research focuses on infrastructure, 0% on application level (SEAMS 2021 — 5-1 verified)
- "Agent-as-configuration" pattern validated by Snapchat's Auton framework but with instantiation-time reconciliation only (6-0 verified)

## Architecture Decision

**Option C: GoalCompiler + FaultPolicy.** GoalCompiler handles planned adaptation (topology changes driven by conditions). FaultPolicy handles unplanned responses (agent failure, provision errors). Two mechanisms for two genuinely different concerns.

**Adaptive layer lives in casehub-desiredstate runtime** (not in casehub-ops-deployment). Pushing the capability upstream enables reuse across all domain modules — deployment, compliance, IoT, infra — not just the deployment domain.

**Signals come from casehub-ras** via a `SituationSource` SPI defined in the desiredstate API. RAS detects situations (volatility-spike, active-breach); GoalCompiler queries active situations and compiles topology accordingly. The runtime has no dependency on RAS — only on its own SPI.

---

## Component 1: Adaptive YAML Format

The deployment YAML gains an `adaptations:` section alongside the existing agents, channels, caseTypes, trust, and endpoints sections:

```yaml
agents:
  - spec:
      agentId: triage-agent
      name: Alert Triage
      slot: worker
  - spec:
      agentId: risk-agent
      name: Risk Monitor
      slot: worker

channels:
  - spec:
      name: trading/work
      semantic: APPEND

trust:
  - spec:
      capability: review
      threshold: 0.7
      minimumObservations: 5

adaptations:
  - name: scale-risk-on-volatility
    trigger:
      situation: volatility-spike
      minConfidence: 0.7
    actions:
      - type: scale
        target: risk-agent
        min: 1
        max: 5

  - name: add-forensics-on-breach
    trigger:
      situation: active-breach
      minConfidence: 0.8
    actions:
      - type: add
        nodes:
          agents:
            - spec:
                agentId: forensics-agent
                name: Forensics Specialist
                slot: worker
          channels:
            - spec:
                name: soc/forensics
                semantic: APPEND

  - name: tighten-trust-on-anomaly
    trigger:
      situation: market-anomaly
      minConfidence: 0.6
    actions:
      - type: update
        target: review-capability
        nodeType: trust
        fields:
          threshold: 0.9
          bootstrapEscalationRequired: true
```

### Action Types

| Action | What it does | Graph operation |
|---|---|---|
| `scale` | Adjust instance count of a base node | Add/remove `DesiredNode` instances with derived IDs (`risk-agent-1`, `risk-agent-2`, ...) |
| `add` | Insert new nodes (full spec inline) | `withNode()` for each declared node |
| `update` | Modify fields of an existing node's spec | `withMutation(UpdateNode(...))` |

### Scale Instance Count

For a scale action with range [min, max] and active situation confidence C:

```
instanceCount = min + (int)((max - min) * confidence)
```

Confidence 0.7 with range [2, 5] → 2 + (int)(3 * 0.7) = 4 instances. Higher confidence = more aggressive scaling.

Derived instance IDs inherit the base spec: `risk-agent-1` through `risk-agent-4`, each with identical AgentNodeSpec fields.

---

## Component 2: SituationSource SPI

Defined in `casehub-desiredstate-api`. No RAS dependency.

```java
public interface SituationSource {
    List<ActiveSituation> activeSituations(String tenancyId);
}

public record ActiveSituation(
    String situationId,
    double confidence,
    Map<String, Object> evidence,
    Instant since
) {}
```

- `situationId` — matches the `trigger.situation` in YAML
- `confidence` — highest detection confidence from accumulated detections (0.0–1.0)
- `evidence` — opaque map from Ganglion DetectionResult, forwarded for audit
- `since` — when the situation was first detected

### Default Implementation

`@DefaultBean` in the runtime returns `List.of()`. No active situations → no adaptations applied → system behaves exactly as today. Adaptive ops activates by dropping RAS + adapter on the classpath.

### RAS Adapter

Separate module (or inside casehub-ras) implements `SituationSource` by querying `SituationStore.findAllActive(tenancyId)` — the new method from casehubio/casehub-ras#20.

```java
@ApplicationScoped
public class RasSituationSource implements SituationSource {
    @Inject SituationStore store;

    @Override
    public List<ActiveSituation> activeSituations(String tenancyId) {
        return store.findAllActive(tenancyId).stream()
            .map(ctx -> new ActiveSituation(
                ctx.situationId(),
                maxConfidence(ctx.detections()),
                mergeEvidence(ctx.detections()),
                ctx.firstSignal()))
            .toList();
    }
}
```

### RAS Dependency

casehubio/casehub-ras#20 — long-lived situation lifecycle. Situations must persist as long as the underlying condition is active, not just until they trigger a case. Required for GoalCompiler to query ongoing situation state.

---

## Component 3: AdaptiveGoalCompiler Wrapper

Lives in casehub-desiredstate runtime. Wraps any domain's `GoalCompiler` output and applies adaptation rules.

```java
@ApplicationScoped
public class AdaptiveGoalCompiler {
    @Inject GoalCompiler<?> delegate;
    @Inject SituationSource situationSource;
    @Inject AdaptationRuleParser ruleParser;

    public DesiredStateGraph compile(String tenancyId) {
        DesiredStateGraph base = delegate.compile();
        List<AdaptationRule> rules = ruleParser.rules();
        if (rules.isEmpty()) return base;

        List<ActiveSituation> situations =
            situationSource.activeSituations(tenancyId);
        if (situations.isEmpty()) return base;

        DesiredStateGraph adapted = base;
        for (AdaptationRule rule : rules) {
            Optional<ActiveSituation> match = situations.stream()
                .filter(s -> s.situationId().equals(rule.trigger().situation()))
                .filter(s -> s.confidence() >= rule.trigger().minConfidence())
                .findFirst();
            if (match.isPresent()) {
                adapted = rule.apply(adapted, match.get());
            }
        }
        return adapted;
    }
}
```

### Key Properties

- **Wraps, doesn't replace.** Domain GoalCompilers unchanged. Adaptive layer only activates if adaptations exist AND matching situations are active.
- **Evaluated every reconciliation cycle.** Situations appear and disappear between cycles. When a situation resolves, the next compilation omits the adaptation and the planner generates DEPROVISION steps for the extra nodes.
- **Rule actions use the existing immutable graph API.** `withNode()`, `withoutNode()`, `withMutation()` — no new graph operations needed.

### Runtime Changes Required

1. `ReconciliationLoop.reconcile()` must re-compile the graph each cycle (currently caches after first compilation)
2. `AdaptationRuleParser` — new class, reads the `adaptations:` YAML section, produces `List<AdaptationRule>`
3. `AdaptationRule` — new runtime type with three action variants: `ScaleAction`, `AddAction`, `UpdateAction`

---

## Component 4: FaultPolicy — Reactive Responses

GoalCompiler handles planned adaptation. FaultPolicy handles unplanned events.

### Deployment FaultPolicy (replaces current no-op)

| Fault | Response |
|---|---|
| `NODE_DESTROYED`, `PROVISION_FAILED` | Self-heal: re-provision the same node |
| `APPROVAL_REJECTED` | Respect the human decision — don't retry |
| `DEPENDENCY_UNAVAILABLE` | Remove dependents temporarily to prevent cascading failures |
| `HUMAN_NODE_TIMEOUT` | Domain-specific — fsitrading/SOC implement their own escalation |

```java
@Override
public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
    return switch (event.type()) {
        case NODE_DESTROYED, PROVISION_FAILED -> {
            DesiredNode node = current.nodes().get(event.node());
            yield node != null
                ? List.of(new GraphMutation.RemoveNode(event.node()),
                          new GraphMutation.AddNode(node))
                : List.of();
        }
        case DEPENDENCY_UNAVAILABLE -> {
            Set<NodeId> dependents = current.dependentsOf(event.node());
            yield dependents.stream()
                .map(GraphMutation.RemoveNode::new)
                .map(m -> (GraphMutation) m)
                .toList();
        }
        case APPROVAL_REJECTED -> List.of();
        default -> List.of();
    };
}
```

### Domain-Specific Policies

fsitrading and SOC provide their own FaultPolicy implementations for domain-specific responses. The FaultPolicyEngine merges mutations from all registered policies.

### Boundary

| Concern | Owner |
|---|---|
| "What should the world look like given current conditions?" | GoalCompiler |
| "Something broke — what do we do right now?" | FaultPolicy |

These don't overlap.

---

## Component 5: EventSource — Triggering Reconciliation

### RAS Situation Changes

The RAS adapter emits events when situations activate or resolve, triggering immediate reconciliation rather than waiting for the 5-minute periodic cycle:

```java
void onSituationActivated(String situationId, String tenancyId) {
    eventSource.emit(new StateEvent(
        NodeId.of("situation:" + situationId),
        NodeStatus.DRIFTED,
        "situation activated"));
}

void onSituationResolved(String situationId, String tenancyId) {
    eventSource.emit(new StateEvent(
        NodeId.of("situation:" + situationId),
        NodeStatus.ABSENT,
        "situation resolved"));
}
```

The node ID is a synthetic signal — not a real node. The ReconciliationLoop receives it, re-compiles the graph (which now includes/excludes adaptations), and plans transitions. The existing debounce window (1s) batches rapid situation changes.

### External Health Checks

Applications can emit health events directly for faster self-healing:

- Heartbeat timeout → `emitDrift(nodeId)`
- Foundation module deregistration → `emit(nodeId, ABSENT, reason)`

This triggers immediate reconciliation rather than waiting for the next periodic ActualStateAdapter check.

---

## Component 6: End-to-End Demo Scenario

### Setup

Quarkus app with casehub-ops-deployment + casehub-desiredstate + RAS adapter. JPA-backed foundation stores (eidos, qhorus, engine, ledger). All state queryable in Postgres.

### fsitrading Overnight Scenario

| Time | Event | System Response | Observable |
|---|---|---|---|
| T0 | App starts | Base topology: 2 strategy agents, 1 risk agent, 1 audit agent, 3 channels, 2 trust policies. All provisioned. | `SELECT * FROM agent` — 4 agents, all PRESENT |
| T1 | strategy-agent-1 dies | ActualStateAdapter: ABSENT. FaultPolicy: self-heal, re-provision. | Agent reappears. Ledger records fault + recovery. |
| T2 | RAS: `volatility-spike` (0.85) | EventSource triggers reconciliation. GoalCompiler: scale risk-agent to 4 instances. Planner: PROVISION risk-agent-2, -3, -4. | 4 risk agents in DB. |
| T3 | RAS: `market-anomaly` (0.7) | GoalCompiler: update trust threshold 0.7 → 0.9, enable bootstrap escalation. | Trust policy updated. Agents below 0.9 require human oversight. |
| T4 | `volatility-spike` resolves | GoalCompiler: no scaling adaptation. Planner: DEPROVISION risk-agent-2, -3, -4. | Back to 1 risk agent. Scale-down in ledger. |

### SOC Active Breach Scenario

| Time | Event | System Response | Observable |
|---|---|---|---|
| T0 | App starts | Base: 2 triage, 1 forensics, 1 compliance agent. | 4 agents in DB. |
| T1 | RAS: `active-breach` (0.9) | Add forensics tier (3 forensics + channel), scale triage to 5, tighten trust → 0.95. | 9 agents, new channel, updated trust. |
| T2 | forensics-agent-2 dies | FaultPolicy: self-heal. Re-provisioned. | Agent back within one cycle. |
| T3 | `active-breach` resolves | Scale back to base. Extra agents deprovisioned. Trust relaxed. | 4 agents. Full chain in ledger. |

### What Makes This Compelling

- `psql` into the database at any point — topology is real, durable, queryable
- Ledger has full audit trail — every provision, deprovision, fault, recovery
- Kill an agent row manually → watch it come back (self-healing)
- No code change between fsitrading and SOC — different YAML, same runtime

---

## Cross-Repo Dependencies

| Repo | What's needed | Issue |
|---|---|---|
| casehub-desiredstate | SituationSource SPI, AdaptiveGoalCompiler, AdaptationRuleParser, ReconciliationLoop re-compile per cycle | To be filed |
| casehub-ras | Long-lived situation lifecycle, findAllActive() query, RAS adapter for SituationSource | casehubio/casehub-ras#20 |
| casehub-ops | Deployment FaultPolicy (replace no-op), EventSource integration, demo YAML for fsitrading and SOC | #25, #26 |
| casehub-fsitrading | Ganglion implementations for market situations, deployment YAML | To be filed |
| casehub-soc | Ganglion implementations for threat situations, deployment YAML | To be filed |

## Non-Goals

- Custom health-check framework — ActualStateAdapter + periodic reconciliation already handles drift detection
- Dynamic GoalCompiler replacing static YAML — base topology is always YAML-declared; adaptations modify it
- Cross-domain dependency graphs — each domain's topology adapts independently (casehub-ops#23 tracks this separately)
