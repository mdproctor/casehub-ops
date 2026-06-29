# Adaptive Ops — Desired-State Topology Adaptation via RAS Situations

**Date:** 2026-06-29
**Issue:** casehubio/casehub-ops#25, casehubio/casehub-ops#26 (driving requirements — implementation tracked by those issues, not closed by this spec)
**Status:** Design — under review

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

**Layer separation:**

- **`SituationSource` SPI** in `casehub-desiredstate-api` — domain-agnostic contract for querying active situations. Any domain module can implement situation-aware adaptation against this SPI.
- **Adaptation logic** in the domain module — `casehub-ops-deployment` for the deployment domain. This includes YAML adaptation rule parsing, rule application, and the `AdaptiveTopologyManager` that orchestrates recompilation and calls `ReconciliationLoop.updateDesired()`.
- **`ReconciliationLoop` unchanged** — the existing `start()`, `updateDesired()`, and event-driven/periodic reconciliation mechanisms are the integration points. No changes to the reconciliation loop's compilation model.

**Signals come from casehub-ras** via a `SituationSource` SPI defined in the desiredstate API. RAS detects situations (volatility-spike, active-breach); the domain's `AdaptiveTopologyManager` queries active situations and compiles topology accordingly. The desiredstate runtime has no dependency on RAS — only on its own SPI.

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
      deactivateBelow: 0.5
      cooldown: PT5M
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
        target: review
        nodeType: trust
        fields:
          threshold: 0.9
          bootstrapEscalationRequired: true
```

### Action Types

| Action | What it does | Graph operation |
|---|---|---|
| `scale` | Adjust instance count of a base node | Add/remove `DesiredNode` instances with derived IDs (`risk-agent~2`, `risk-agent~3`, ...) |
| `add` | Insert new nodes (full spec inline) | `withNode()` for each declared node |
| `update` | Modify fields of an existing node's spec | `withMutation(UpdateNode(...))` |

### Target Resolution

For `update` and `scale` actions, `target` matches against the `nodeId()` value of node specs in the base topology:

| Node type | `nodeId()` returns | Example target |
|---|---|---|
| Agent | `agentId` | `risk-agent` |
| Trust policy | `capability` | `review` |
| Channel | `name` | `trading/work` |
| Case type | `namespace:name:version` | `trading:market-order:1` |
| Endpoint | `path` | `/api/trading/submit` |

When `nodeType` is specified, matching is restricted to nodes of that type. If no match is found, the adaptation rule is skipped with a warning log.

### Scale Instance Count

For a scale action with range [min, max], active situation confidence C, and trigger minConfidence T:

```
effective = (C - T) / (1.0 - T)
instanceCount = min + (int)((max - min) * effective)
```

This normalizes confidence against the activation threshold so the full [min, max] range maps to the useful confidence range [T, 1.0].

Example with minConfidence=0.7, range [1, 5]:

| Confidence | effective | instanceCount |
|---|---|---|
| 0.70 | 0.00 | 1 (min) |
| 0.85 | 0.50 | 3 |
| 1.00 | 1.00 | 5 (max) |

The base node (e.g., `risk-agent`) is always present. Derived instances use the `~` separator: `risk-agent~2` through `risk-agent~N`, each with identical `AgentNodeSpec` fields except `agentId`.

### Scale-Down Ordering

When instanceCount decreases, instances are removed highest-numbered first (LIFO). `risk-agent~5` is deprovisioned before `risk-agent~4`. Graceful shutdown of in-flight work is the `TransitionExecutor`'s responsibility — the scaling policy determines *which* nodes to remove, not *how*.

### NodeId Collision Validation

Scale actions derive instance IDs using the pattern `{target}~{n}`. At YAML parse time, the parser validates that no base topology node has an ID matching `{target}~{n}` for any n in [2, max]. A collision is a configuration error — fail fast with a clear message.

### Hysteresis and Cooldown

To prevent churn from confidence oscillating near the threshold:

- **`deactivateBelow`** — adaptation deactivates when confidence drops below this value. The rule re-activates when confidence rises above `minConfidence`. Defaults to `minConfidence` if omitted (no hysteresis band).
- **`cooldown`** — ISO 8601 duration. Minimum time between adaptation state changes (activate or deactivate). Defaults to `PT0S` (no cooldown).

Example: with `minConfidence: 0.7` and `deactivateBelow: 0.5`, confidence oscillating between 0.65 and 0.72 stays in the active state (above 0.5), avoiding churn. Only a sustained drop below 0.5 deactivates.

### Conflict Detection

When multiple simultaneously active adaptations modify the same node, YAML declaration order defines precedence — later rules override earlier ones for the same node. A warning is logged when this occurs, including rule names and the affected node, so the operator can verify the ordering is intentional.

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

Separate module (or inside casehub-ras) implements `SituationSource` by querying `SituationStore` — using the new long-lived situation API from casehubio/casehub-ras#20.

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

casehubio/casehub-ras#20 — long-lived situation lifecycle. This is a substantial design change to RAS, not a trivial method addition:

- `SituationStore` currently has `find(situationId, correlationKey, tenancyId)`, `save()`, `remove()`, `removeExpired()` — no concept of "all active" situations
- `SituationContext` requires `correlationKey` as part of its identity — querying "all active" requires a new index or query model
- `TriggerDecision` is `{CREATE_CASE, CONTINUE_ACCUMULATING, DISCARD}` — no variant for situations that persist beyond case triggering
- The lifecycle where situations exist independently of case creation is a new concept for RAS

The `SituationSource` SPI defines what this spec *needs* from RAS. Issue ras#20 owns the design of how RAS *delivers* it. The `RasSituationSource` adapter above is illustrative — the actual implementation depends on ras#20's design decisions.

---

## Component 3: AdaptiveTopologyManager

**Lives in `casehub-ops-deployment`** — deployment-domain-specific. It knows about `DeploymentGoals`, `DeploymentGoalCompiler`, and the deployment YAML adaptation format.

The `ReconciliationLoop` has no reference to any `GoalCompiler`. It receives a pre-compiled `DesiredStateGraph` via `start(tenancyId, graph)` and stores it in an `AtomicReference`. The only way to change the desired graph is externally via `updateDesired(tenancyId, newGraph)`. The `AdaptiveTopologyManager` sits between goal loading and the reconciliation loop, re-compiling and pushing graph updates when situations change.

```java
@ApplicationScoped
public class AdaptiveTopologyManager {

    @Inject DeploymentGoalCompiler compiler;
    @Inject DesiredStateGraphFactory graphFactory;
    @Inject SituationSource situationSource;
    @Inject ReconciliationLoop reconciliationLoop;
    @Inject AdaptationRuleParser ruleParser;

    private DeploymentGoals goals;
    private List<AdaptationRule> rules;
    private final Map<String, Instant> lastChangePerRule = new HashMap<>();
    private final Map<String, Boolean> activePerRule = new HashMap<>();

    public void initialize(String tenancyId, DeploymentGoals goals) {
        this.goals = goals;
        this.rules = ruleParser.rules();
        DesiredStateGraph adapted = compileAdapted(tenancyId);
        reconciliationLoop.start(tenancyId, adapted);
    }

    public void onSituationChange(String tenancyId) {
        DesiredStateGraph adapted = compileAdapted(tenancyId);
        reconciliationLoop.updateDesired(tenancyId, adapted);
        reconciliationLoop.requestReconciliation(tenancyId);
    }

    private DesiredStateGraph compileAdapted(String tenancyId) {
        DesiredStateGraph base = compiler.compile(goals, graphFactory);
        if (rules.isEmpty()) return base;

        List<ActiveSituation> situations =
            situationSource.activeSituations(tenancyId);
        if (situations.isEmpty()) return base;

        DesiredStateGraph adapted = base;
        Set<NodeId> modifiedNodes = new HashSet<>();

        for (AdaptationRule rule : rules) {
            Optional<ActiveSituation> match = situations.stream()
                .filter(s -> s.situationId().equals(rule.trigger().situation()))
                .filter(s -> shouldActivate(rule, s))
                .findFirst();

            if (match.isPresent()) {
                Set<NodeId> targets = rule.targetNodeIds(base);
                for (NodeId t : targets) {
                    if (modifiedNodes.contains(t)) {
                        LOG.warning("Conflict: rule '%s' modifies node '%s' "
                            + "already modified by an earlier rule"
                            .formatted(rule.name(), t));
                    }
                }
                adapted = rule.apply(adapted, match.get());
                modifiedNodes.addAll(targets);
            }
        }
        return adapted;
    }

    private boolean shouldActivate(AdaptationRule rule, ActiveSituation situation) {
        boolean currentlyActive = activePerRule.getOrDefault(rule.name(), false);

        // Hysteresis: use deactivateBelow threshold for already-active rules
        double threshold = currentlyActive
            ? rule.trigger().deactivateBelow()
            : rule.trigger().minConfidence();

        boolean shouldBeActive = situation.confidence() >= threshold;

        // Cooldown: enforce minimum time between state changes
        if (shouldBeActive != currentlyActive && rule.trigger().cooldown() != null) {
            Instant lastChange = lastChangePerRule.get(rule.name());
            if (lastChange != null) {
                Duration elapsed = Duration.between(lastChange, Instant.now());
                if (elapsed.compareTo(rule.trigger().cooldown()) < 0) {
                    return currentlyActive; // maintain current state during cooldown
                }
            }
        }

        // Track state transitions (both activation AND deactivation)
        if (shouldBeActive != currentlyActive) {
            lastChangePerRule.put(rule.name(), Instant.now());
            activePerRule.put(rule.name(), shouldBeActive);
        }
        return shouldBeActive;
    }
}
```

### Key Properties

- **Domain-specific.** Knows about `DeploymentGoals` and `DeploymentGoalCompiler`. Other domains implement their own adaptation logic against the `SituationSource` SPI.
- **Uses correct GoalCompiler signature.** Calls `compiler.compile(goals, graphFactory)` — the actual two-parameter `GoalCompiler<G>` SPI.
- **No CDI ambiguity.** Injects `DeploymentGoalCompiler` (the concrete class), not `GoalCompiler<?>`. Single-domain-per-classpath constraint (ops ARC42STORIES §2) means no ambiguity.
- **ReconciliationLoop unchanged.** Uses `start()`, `updateDesired()`, and `requestReconciliation()` — no modifications to the loop's internal compilation model.
- **Re-compiles from base each time.** Never incrementally patches the adapted graph. Base → adaptations → result. This ensures consistency when situations appear/disappear.
- **Rule actions use the existing immutable graph API.** `withNode()`, `withoutNode()`, `withMutation()` — no new graph operations needed.

### Runtime Changes Required

1. `AdaptationRuleParser` — new class in `casehub-ops-deployment`, reads the `adaptations:` YAML section, produces `List<AdaptationRule>`
2. `AdaptationRule` — new type in `casehub-ops-api` with three action variants: `ScaleAction`, `AddAction`, `UpdateAction`
3. `ReconciliationLoop.requestReconciliation(String tenancyId)` — new method in `casehub-desiredstate-runtime`, triggers a debounced reconciliation for a tenant without requiring a synthetic `StateEvent`

---

## Component 4: FaultPolicy — Reactive Responses

GoalCompiler handles planned adaptation. FaultPolicy handles unplanned events — but only through graph mutations. Retry and re-provisioning are `ReconciliationLoop` responsibilities.

### Deployment FaultPolicy

Self-healing is built into the reconciliation cycle. When a node is destroyed:

1. `ActualStateAdapter.readActual()` reports `NodeStatus.ABSENT`
2. `TransitionPlanner.plan()` sees ABSENT vs desired → generates a PROVISION step
3. The node is re-provisioned automatically on the next cycle

FaultPolicy does not need to intervene. `RemoveNode` mutations from fault policies destroy graph topology — this is a documented anti-pattern (ARC42STORIES §8: "RemoveNode mutation from fault policy destroys the entire graph topology for a subtree — downstream stages become permanently orphaned").

All existing domain FaultPolicies (`InfraFaultPolicy`, `IoTFaultPolicy`, `ComplianceFaultPolicy`, `DeploymentFaultPolicy`) return `List.of()` — this is the correct pattern.

```java
@ApplicationScoped
public class DeploymentFaultPolicy implements FaultPolicy {
    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        return List.of();
    }
}
```

| Fault | Response | Rationale |
|---|---|---|
| `NODE_DESTROYED`, `PROVISION_FAILED` | No mutation — reconciliation re-provisions | Self-healing is a reconciliation loop concern, not a fault policy decision |
| `APPROVAL_REJECTED` | No mutation — respect the human decision | Approved by the ops ARC42STORIES human-node pattern |
| `DEPENDENCY_UNAVAILABLE` | No mutation — leave dependents in desired graph | Removing dependents destroys edges permanently (anti-pattern); the planner handles ordering |
| `HUMAN_NODE_TIMEOUT` | No mutation — domain apps can override | fsitrading/SOC provide their own FaultPolicy for domain-specific escalation |

### Boundary

| Concern | Owner |
|---|---|
| "What should the world look like given current conditions?" | GoalCompiler + AdaptiveTopologyManager |
| "Something broke — what do we do right now?" | ReconciliationLoop (retry/re-provision) + FaultPolicy (graph mutations if needed) |

---

## Component 5: Reconciliation Triggering

### Situation-Driven Reconciliation

When a RAS situation activates or resolves, the `AdaptiveTopologyManager`:

1. Re-compiles the adapted graph from base goals + current situations
2. Calls `reconciliationLoop.updateDesired(tenancyId, adaptedGraph)` to swap the desired graph
3. Calls `reconciliationLoop.requestReconciliation(tenancyId)` to trigger immediate reconciliation

The `requestReconciliation()` method triggers a debounced reconciliation cycle using the same debounce window as event-driven reconciliation. This avoids emitting synthetic `StateEvent`s with fake `NodeId`s that don't correspond to real graph nodes — `StateEvent` should only carry real node status changes.

### External Health Checks

Applications can emit health events directly for faster self-healing:

- Heartbeat timeout → `emit StateEvent(nodeId, DRIFTED, reason)`
- Foundation module deregistration → `emit StateEvent(nodeId, ABSENT, reason)`

These use real node IDs with real statuses — semantically correct use of the event system. They trigger immediate reconciliation rather than waiting for the next periodic `ActualStateAdapter` check.

### Periodic Re-Sync

The existing 5-minute periodic re-sync continues to work. The `AdaptiveTopologyManager.onSituationChange()` is called on each situation event for immediate response. The periodic cycle serves as a safety net for missed events.

---

## Component 6: End-to-End Demo Scenario

### Setup

Quarkus app with casehub-ops-deployment + casehub-desiredstate + RAS adapter. JPA-backed foundation stores (eidos, qhorus, engine, ledger). All state queryable in Postgres.

### fsitrading Overnight Scenario

| Time | Event | System Response | Observable |
|---|---|---|---|
| T0 | App starts | `AdaptiveTopologyManager.initialize()`: base topology — 2 strategy agents, 1 risk agent, 1 audit agent, 3 channels, 2 trust policies. All provisioned. | `SELECT * FROM agent` — 4 agents, all PRESENT |
| T1 | strategy-agent-1 dies | `ActualStateAdapter`: ABSENT. Reconciliation loop: planner sees ABSENT vs desired, generates PROVISION step. Re-provisioned. | Agent reappears. Ledger records fault + recovery. |
| T2 | RAS: `volatility-spike` (0.85) | `AdaptiveTopologyManager.onSituationChange()`: re-compile with scale adaptation. effective=(0.85-0.7)/0.3=0.5, instanceCount=3. `updateDesired()` + `requestReconciliation()`. Planner: PROVISION risk-agent~2, risk-agent~3. | 3 risk agents in DB. |
| T3 | RAS: `market-anomaly` (0.7) | Re-compile: update trust threshold 0.7 → 0.9, enable bootstrap escalation. | Trust policy updated. Agents below 0.9 require human oversight. |
| T4 | `volatility-spike` resolves | Re-compile: no scaling adaptation. Planner: DEPROVISION risk-agent~3, risk-agent~2 (LIFO). | Back to 1 risk agent. Scale-down in ledger. |

### SOC Active Breach Scenario

| Time | Event | System Response | Observable |
|---|---|---|---|
| T0 | App starts | Base: 2 triage, 1 forensics, 1 compliance agent. | 4 agents in DB. |
| T1 | RAS: `active-breach` (0.9) | Add forensics tier (3 forensics + channel), scale triage to 5, tighten trust → 0.95. | 9 agents, new channel, updated trust. |
| T2 | forensics-agent~2 dies | Reconciliation loop: ABSENT → PROVISION. Re-provisioned. | Agent back within one cycle. |
| T3 | `active-breach` confidence drops to 0.4 (below `deactivateBelow: 0.5`) | Scale back to base. Extra agents deprovisioned. Trust relaxed. | 4 agents. Full chain in ledger. |

### What Makes This Compelling

- `psql` into the database at any point — topology is real, durable, queryable
- Ledger has full audit trail — every provision, deprovision, fault, recovery
- Kill an agent row manually → watch it come back (self-healing via reconciliation, no FaultPolicy needed)
- No code change between fsitrading and SOC — different YAML, same runtime

---

## Cross-Repo Dependencies

| Repo | What's needed | Issue |
|---|---|---|
| casehub-desiredstate | `SituationSource` SPI + `ActiveSituation` record in API, `DefaultSituationSource` @DefaultBean in runtime, `requestReconciliation()` method on `ReconciliationLoop` | casehubio/casehub-desiredstate#49 |
| casehub-ras | Long-lived situation lifecycle, `findAllActive()` query model, `RasSituationSource` adapter | casehubio/casehub-ras#20 |
| casehub-ops | `AdaptiveTopologyManager`, `AdaptationRuleParser`, `AdaptationRule` types, deployment YAML `adaptations:` section, demo YAML for fsitrading and SOC | #25, #26 |
| casehub-fsitrading | Ganglion implementations for market situations, deployment YAML | casehubio/casehub-fsitrading#1 |
| casehub-soc | Ganglion implementations for threat situations, deployment YAML | casehubio/casehub-soc#1 |

## Non-Goals

- Custom health-check framework — ActualStateAdapter + periodic reconciliation already handles drift detection
- Dynamic GoalCompiler replacing static YAML — base topology is always YAML-declared; adaptations modify it
- Cross-domain dependency graphs — each domain's topology adapts independently (casehub-ops#23 tracks this separately)
- FaultPolicy-based self-healing — reconciliation loop already handles re-provisioning of destroyed/failed nodes
