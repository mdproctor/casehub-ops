# Deployment Module Design — Application-Level Topology Provisioning

**Issue:** casehubio/casehub-ops#7
**Date:** 2026-06-17
**Status:** Approved (revision 4 — final review)
**Extends:** [#2 spec](2026-06-16-deployment-agent-topology-design.md)

## Problem

The deployment module from #2 handles registration-level provisioning — ensuring agents, channels, case types, and trust policies are registered in the right foundation modules. But for a CaseHub application to be practically deployable, the YAML declaration needs to carry:

- **Provider-specific agent configuration** — not just "this agent exists" but "this agent runs with these tools, this model, this system prompt" (claudony, openclaw settings)
- **Case definition file references** — full case definitions loaded from YAML files, not just identity metadata
- **Richer drift detection** — comparing configuration equality, not just presence/absence

Today this configuration is scattered across application.properties, Java initializer beans, classpath YAML resources, and CDI beans. The deployment module should consolidate it into a single declarative surface.

## Scope

This spec covers work within **casehub-ops only**. Provider-specific config is stored and queryable but not yet consumed by claudony/openclaw. Companion issues are filed for cross-repo work. Connector registration remains external — channels reference connector IDs but connectors themselves are not deployment-managed nodes.

## Key Design Decisions

**Provider config is typed marker + opaque payload.** A `ProviderConfig(String providerName, Map<String, Object> config)` record. The deployment module stores it without interpreting the provider-specific fields. Drift detection compares via deep equality on the map. Providers define their own schema when they consume it. Avoids coupling ops to provider internals.

**GoalCompiler resolves definition files at compile time.** A `definitionFile` path on `CaseTypeNodeSpec` is resolved into an immutable `Map<String, Object>` payload during compilation. By the time nodes enter the graph, they are fully resolved. The compiler's job is transforming declarations into a resolved graph — file references are declaration-time concerns.

**Case definition payload is `Map<String, Object>`, not `CaseDefinition`.** `CaseDefinition` is a mutable JavaBean (`setTitle()`, `getCapabilities().add(...)`) with a `hashCode()` that uses only namespace + name + version — ignoring capabilities, workers, bindings, milestones, and goals. Putting it on an immutable record breaks the value-type contract and defeats spec hash drift detection. The raw parsed YAML content as `Map<String, Object>` is immutable via `Collections.unmodifiableMap()`, has correct recursive `hashCode()`/`equals()` for nested maps and lists, and decouples the spec from engine-api's mutable domain class. The handler constructs `CaseDefinition` from the payload at provision time.

**Opaque YAML maps use `Collections.unmodifiableMap()`, not `Map.copyOf()`.** `Map.copyOf()` throws NPE on null values — a common YAML occurrence (`someField: null`, `someField: ~`, empty values). `Collections.unmodifiableMap(new LinkedHashMap<>(input))` preserves null values while maintaining immutability. This applies to `ProviderConfig.config`, `CaseTypeNodeSpec.definitionPayload`, and recursive deep-freezing in the definition loader. `Map.copyOf()` remains correct for maps with known non-null value types (e.g. `axisVocabularies`, `qualityFloors`).

**Connectors are external prerequisites, not node types.** The deployment YAML references connector IDs on channels but doesn't declare the connectors themselves. Connectors are infrastructure that exists independently of any CaseHub deployment. Adding a node type would require ops to depend on casehub-connectors runtime and know how to create Slack apps, configure IMAP accounts, etc.

**Layered drift detection with SPI delegation.** Two layers, checked in this order:
1. SPI-based external drift check — detects the node's actual state in the foundation module (ABSENT, PRESENT, DRIFTED, UNKNOWN)
2. Spec hash comparison (internal) — for nodes that are externally PRESENT, checks whether the desired declaration changed since last provision

External truth first, internal bookkeeping second. This ensures first-run nodes report ABSENT (not DRIFTED), and that external changes are always detected regardless of spec hash state.

**Drift resolution requires a TransitionPlanner fix (companion issue).** The current `TransitionPlanner.plan()` only handles ABSENT/UNKNOWN (→ provision) and PRESENT-not-in-desired (→ deprovision). DRIFTED has no code path — the planner silently ignores it. This means drift detection correctly surfaces drift via OTel traces (`desiredstate.drift.count` attribute) and fires FaultEvents, but no automatic re-provisioning occurs. The `DeploymentFaultPolicy` cannot fix this via graph mutations — `RemoveNode` doesn't trigger deprovision for DRIFTED nodes (planner checks `status == PRESENT` specifically), and `UpdateNode` only modifies the desired graph without changing actual state.

The correct fix is a one-line change in `TransitionPlanner`: treat DRIFTED like ABSENT (re-provision). All deployment provisioners are idempotent (upsert semantics), so re-provisioning a drifted node is safe. This is a prerequisite companion issue on casehub-desiredstate — without it, drift detection provides observability but not self-healing.

**NodeDriftChecker SPI in casehub-ops-api, not casehub-desiredstate-api.** `NodeDriftChecker` is a deployment-domain dispatch pattern — not a platform-level SPI. The infra module uses a completely different decomposition (`InfraBackend.readState()`). If `NodeDriftChecker` were a platform concept, both domains would use it. Placing it in desiredstate-api would set a precedent that every domain module's internal dispatch pattern becomes a platform SPI.

The SPI lives in `io.casehub.ops.api.deployment`. Foundation bridge modules (e.g. `casehub-eidos-desiredstate`) depend on `casehub-ops-api` (`provided`) — this is correct because they are deployment-domain bridges by definition. The dependency chain `casehub-eidos-desiredstate → casehub-ops-api → casehub-desiredstate-api` has no circular dependencies. This also eliminates the prerequisite companion issue on casehub-desiredstate.

## API Type Changes (casehub-ops-api)

### ProviderConfig — new record

```java
package io.casehub.ops.api.deployment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProviderConfig(String providerName, Map<String, Object> config) {
    public ProviderConfig {
        if (providerName == null || providerName.isBlank())
            throw new IllegalArgumentException("providerName is required");
        config = config != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(config))
                : Map.of();
    }
}
```

`Collections.unmodifiableMap()` instead of `Map.copyOf()` because YAML-parsed maps may contain null values (`someField: null`, `someField: ~`, empty values). `Map.copyOf()` throws NPE on null values.

### NodeDriftChecker — new SPI interface

```java
package io.casehub.ops.api.deployment;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;

public interface NodeDriftChecker {
    NodeStatus check(NodeSpec spec, String tenancyId);
    String nodeType();
}
```

`nodeType()` returns the type string this checker handles (`"agent"`, `"channel"`, `"case_type"`, `"trust_policy"`). The `ActualStateAdapter` discovers all `NodeDriftChecker` beans via CDI `Instance<NodeDriftChecker>`, indexes by `nodeType()`, and dispatches.

The checker receives `NodeSpec` and casts internally — it knows what spec type it handles. This avoids generics complications with CDI discovery (`Instance<NodeDriftChecker>` is simple; `Instance<NodeDriftChecker<AgentNodeSpec>>` is not).

### AgentNodeSpec — two new fields, complete signature

```java
public record AgentNodeSpec(
        String agentId,
        String name,
        String slot,
        String provider,
        String modelFamily,
        String modelVersion,
        String version,
        String weightsFingerprint,
        String domainVocabulary,
        String slotVocabulary,
        String dispositionVocabulary,
        Map<DispositionAxis, String> axisVocabularies,
        List<AgentCapability> capabilities,
        AgentDisposition disposition,
        String jurisdiction,
        String dataHandlingPolicy,
        String briefing,                        // NEW — AgentDescriptor.briefing
        List<ProviderConfig> providerConfigs    // NEW — deployment-specific, not on AgentDescriptor
) implements DeploymentNodeSpec {

    public AgentNodeSpec {
        if (agentId == null || agentId.isBlank())
            throw new IllegalArgumentException("agentId is required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name is required");
        if (slot == null || slot.isBlank())
            throw new IllegalArgumentException("slot is required");
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        axisVocabularies = axisVocabularies != null ? Map.copyOf(axisVocabularies) : null;
        providerConfigs = providerConfigs != null ? List.copyOf(providerConfigs) : List.of();
    }
    // ...
}
```

`briefing` follows `dataHandlingPolicy` to match `AgentDescriptor` field order (tenancyId is excluded — comes from `ProvisionContext`). `providerConfigs` goes last as deployment-specific, not present on `AgentDescriptor`. Defaults to `List.of()` for consistency with the `capabilities` field pattern.

### CaseTypeNodeSpec — two new fields

```java
public record CaseTypeNodeSpec(
        String namespace,
        String name,
        String version,
        String title,
        String summary,
        String definitionFile,                      // NEW — path to YAML case definition
        Map<String, Object> definitionPayload       // NEW — resolved YAML content (immutable)
) implements DeploymentNodeSpec {

    public CaseTypeNodeSpec {
        if (namespace == null || namespace.isBlank())
            throw new IllegalArgumentException("namespace is required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name is required");
        if (version == null || version.isBlank())
            throw new IllegalArgumentException("version is required");
        definitionPayload = definitionPayload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(definitionPayload))
                : null;
    }
    // ...
}
```

`definitionFile` is the YAML file path consumed by the compiler. `definitionPayload` is the parsed YAML content as an immutable map — `Collections.unmodifiableMap()` instead of `Map.copyOf()` to preserve null values from YAML parsing. Correct `hashCode()`/`equals()` for drift detection. The handler constructs `CaseDefinition` from the payload at provision time.

### DeploymentNodeSpec sealed permits — unchanged

Still exactly 4 types: `AgentNodeSpec`, `ChannelNodeSpec`, `CaseTypeNodeSpec`, `TrustPolicyNodeSpec`.

## Drift Detection

### Default NodeDriftChecker implementations (deployment module)

| Implementation | nodeType | Logic |
|---|---|---|
| `AgentDriftChecker` | `"agent"` | `AgentRegistry.findById()` → ABSENT if missing, compares capabilities for DRIFTED |
| `ChannelDriftChecker` | `"channel"` | `ChannelService.findByName()` → ABSENT if missing, compares mutable fields for DRIFTED |
| `CaseTypeDriftChecker` | `"case_type"` | `CaseTypeProvisionHandler.isRegistered()` → ABSENT if not in internal map, PRESENT otherwise |
| `TrustPolicyDriftChecker` | `"trust_policy"` | Reads from internal policy map, compares all fields |

All `@ApplicationScoped` with no special priority. Foundation bridge modules (future) override with `@Alternative @Priority(1)`.

`CaseTypeDriftChecker` uses the existing `CaseTypeProvisionHandler.isRegistered()` method (backed by the internal `ConcurrentHashMap`). This is not a regression from #2 — the current adapter already uses this check. `UNKNOWN` would only be appropriate when we genuinely cannot determine state.

### Foundation bridge modules (companion issues — not implemented in this branch)

Each foundation repo provides an optional bridge module:
- `casehub-eidos-desiredstate` — rich agent drift detection (full field-by-field comparison including disposition, vocabularies, briefing)
- `casehub-qhorus-desiredstate` — rich channel drift detection (all fields including immutable ones, connector binding state)
- `casehub-engine-desiredstate` — case definition drift detection (requires `CaseDefinitionRegistry.findByIdentity()`)

These modules depend on `casehub-ops-api` (`provided`), implement `NodeDriftChecker`, and activate by classpath presence.

### SpecHashStore — new shared bean

```java
@ApplicationScoped
public class SpecHashStore {
    private final ConcurrentHashMap<NodeId, Integer> hashes = new ConcurrentHashMap<>();

    public void record(NodeId id, NodeSpec spec) {
        hashes.put(id, deepHash(spec));
    }

    public void remove(NodeId id) {
        hashes.remove(id);
    }

    public boolean hasDrifted(NodeId id, NodeSpec spec) {
        Integer stored = hashes.get(id);
        if (stored == null) return true;
        return stored != deepHash(spec);
    }

    private int deepHash(NodeSpec spec) { /* record hashCode() */ }
}
```

Shared between `DeploymentNodeProvisioner` (writes on successful provision) and `DeploymentActualStateAdapter` (reads on drift check). Java records generate field-by-field `hashCode()` automatically. `Map.hashCode()` recurses correctly for nested provider config maps and definition payloads.

### DeploymentActualStateAdapter — refactored

Replaces the current hardcoded per-type methods with layered dispatch. External check first, spec hash second:

```java
@ApplicationScoped
public class DeploymentActualStateAdapter implements ActualStateAdapter {

    private final Map<String, NodeDriftChecker> checkers;
    private final SpecHashStore specHashStore;

    @Inject
    public DeploymentActualStateAdapter(
            Instance<NodeDriftChecker> driftCheckers,
            SpecHashStore specHashStore) {
        this.checkers = new HashMap<>();
        for (var checker : driftCheckers) {
            this.checkers.put(checker.nodeType(), checker);
        }
        this.specHashStore = specHashStore;
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
        // Layer 1: external — does the node exist and match in the foundation module?
        NodeDriftChecker checker = checkers.get(node.type().value());
        NodeStatus external = (checker != null)
                ? checker.check(node.spec(), "default")  // tenancyId interim
                : NodeStatus.UNKNOWN;

        if (external == NodeStatus.ABSENT) return NodeStatus.ABSENT;
        if (external == NodeStatus.DRIFTED) return NodeStatus.DRIFTED;

        // Layer 2: spec hash — only for PRESENT nodes, did the declaration change?
        if (external == NodeStatus.PRESENT && specHashStore.hasDrifted(node.id(), node.spec())) {
            return NodeStatus.DRIFTED;
        }
        return external;  // PRESENT, UNKNOWN — unchanged
    }
}
```

The existing per-type logic (`checkAgentStatus`, `checkChannelStatus`, `mutableFieldsMatch`, `allowedTypesMatch`, `deniedTypesMatch`) moves into the default `NodeDriftChecker` implementations unchanged.

## GoalCompiler Enrichment

### Definition file resolution

The compiler gains a case type enrichment step. When `definitionFile` is set, it loads the YAML content and creates a new `CaseTypeNodeSpec` with `definitionPayload` populated:

```java
private List<GoalEntry<CaseTypeNodeSpec>> resolveCaseTypes(
        List<GoalEntry<CaseTypeNodeSpec>> entries) {
    return entries.stream().map(entry -> {
        var spec = entry.spec();
        if (spec.definitionFile() != null && spec.definitionPayload() == null) {
            Map<String, Object> payload = definitionLoader.load(spec.definitionFile());
            var resolved = new CaseTypeNodeSpec(
                spec.namespace(), spec.name(), spec.version(),
                spec.title(), spec.summary(),
                spec.definitionFile(), payload);
            return new GoalEntry<>(resolved, entry.dependsOn());
        }
        return entry;
    }).toList();
}
```

### DefinitionPayloadLoader — new class

```java
@ApplicationScoped
public class DefinitionPayloadLoader {
    public Map<String, Object> load(String definitionFile) { ... }
}
```

Resolves `definitionFile` using a two-step strategy:
1. **Classpath first** — `Thread.currentThread().getContextClassLoader().getResourceAsStream(path)`. This is the canonical Quarkus resource resolution for bundled YAML.
2. **Filesystem fallback** — `java.nio.file.Path.of(path)` if not found on classpath. Supports operator-supplied deployment YAML that lives outside the application JAR.

Parses YAML into `Map<String, Object>` using Jackson `ObjectMapper` with YAML factory. Returns a deeply frozen immutable copy using recursive `Collections.unmodifiableMap()` / `Collections.unmodifiableList()` — not `Map.copyOf()` / `List.copyOf()`, which throw NPE on null values that YAML parsers commonly produce. Throws `IllegalArgumentException` for missing or malformed files.

### DeploymentGoalLoader — new utility class

Handles the YAML parsing layer above the compiler:

```java
@ApplicationScoped
public class DeploymentGoalLoader {
    public DeploymentGoals load(String path) { ... }
    public DeploymentGoals loadDirectory(String directoryPath) { ... }
    public DeploymentGoals merge(DeploymentGoals... fragments) { ... }
}
```

- `load(path)` — parses a single YAML file into `DeploymentGoals`. Uses classpath-first, filesystem-fallback resolution (same strategy as `DefinitionPayloadLoader`).
- `loadDirectory(path)` — **filesystem only** — reads all `.yaml`/`.yml` files in the directory, parses each, merges. Classpath directory listing is unreliable in Java (`ClassLoader.getResource(dir)` behavior varies between exploded classpath and JAR). Directory scanning is a deployment-time concern where files are on disk.
- `merge(fragments)` — concatenates agent/channel/caseType/trust lists from multiple fragments

Supports both single-file and multi-file directory structures:

Single-file:
```yaml
# topology.yaml — all sections in one file
agents: [...]
channels: [...]
caseTypes: [...]
trust: [...]
```

Multi-file directory:
```
deployment/
├── agents.yaml
├── channels.yaml
├── cases.yaml
└── trust.yaml
```

Section names in the YAML are the same regardless of file structure. The loader merges by concatenation.

## Handler Changes

### AgentProvisionHandler

Updated constructor call with `briefing` in correct position (18th parameter, after `tenancyId`):

```java
AgentDescriptor descriptor = new AgentDescriptor(
        spec.agentId(),
        spec.name(),
        spec.version(),
        spec.provider(),
        spec.modelFamily(),
        spec.modelVersion(),
        spec.weightsFingerprint(),
        spec.domainVocabulary(),
        spec.slotVocabulary(),
        spec.dispositionVocabulary(),
        spec.axisVocabularies(),
        spec.slot(),
        spec.capabilities(),
        spec.disposition(),
        spec.jurisdiction(),
        spec.dataHandlingPolicy(),
        context.tenancyId(),
        spec.briefing()       // briefing — 18th param, after tenancyId
);
```

Provider config is stored in a dedicated `DeploymentProviderConfigStore` bean (see below), not in the handler itself. This follows the established pattern from `DeploymentTrustRoutingPolicyProvider` — a dedicated `@ApplicationScoped` bean that the handler writes to and consumers read from.

### DeploymentProviderConfigStore — new bean

```java
@ApplicationScoped
public class DeploymentProviderConfigStore {
    private final ConcurrentHashMap<String, List<ProviderConfig>> configs = new ConcurrentHashMap<>();

    public void store(String agentId, List<ProviderConfig> providerConfigs) {
        configs.put(agentId, List.copyOf(providerConfigs));
    }

    public List<ProviderConfig> forAgent(String agentId) {
        return configs.getOrDefault(agentId, List.of());
    }

    public void remove(String agentId) {
        configs.remove(agentId);
    }
}
```

`AgentProvisionHandler` injects this bean and calls `store()` on provision, `remove()` on deprovision. Future bridge modules in claudony/openclaw inject the same bean to read provider config.

### CaseTypeProvisionHandler

- When `spec.definitionPayload()` is non-null, constructs a `CaseDefinition` from the payload and registers it (full definition)
- Skeleton fallback (namespace + name + version + title + summary only) preserved when `definitionPayload` is null

### DeploymentNodeProvisioner

- On successful provision: calls `specHashStore.record(nodeId, spec)`
- On deprovision: calls `specHashStore.remove(nodeId)`

### TrustPolicyProvisionHandler — move to handler/ subpackage

Currently at `io.casehub.ops.deployment.TrustPolicyProvisionHandler`. Move to `io.casehub.ops.deployment.handler.TrustPolicyProvisionHandler` for consistency with the other three handlers.

### ChannelProvisionHandler

No changes needed.

## Extended YAML Schema

New fields shown alongside the existing schema from #2:

```yaml
agents:
  - agentId: code-reviewer
    name: Code Reviewer
    slot: worker
    provider: anthropic
    modelFamily: claude
    modelVersion: opus-4
    version: "1.0"
    weightsFingerprint: null
    domainVocabulary: null
    slotVocabulary: null
    dispositionVocabulary: null
    axisVocabularies:
      SOCIAL_ORIENTATION: "urn:casehub:vocab:social"
    capabilities:
      - name: code-review
        qualityHint: 0.9
        tags: [java, quarkus]
    disposition:
      socialOrient: collaborative
      ruleFollowing: strict
      riskAppetite: conservative
    jurisdiction: EU
    dataHandlingPolicy: gdpr-compliant
    briefing: "Reviews pull requests for code quality"  # NEW — AgentDescriptor.briefing
    providerConfigs:                                     # NEW — List<ProviderConfig>
      - providerName: claudony
        config:
          systemPrompt: prompts/code-reviewer.md
          tools: [read, write, bash, mcp]
          mcpServers: [github, jira]
          maxConcurrentSessions: 3
          sessionTimeout: 30m
      - providerName: openclaw
        config:
          sessionKey: code-reviewer
          gatewayUrl: "${OPENCLAW_GATEWAY_URL}"

caseTypes:
  - namespace: io.casehub.devtown
    name: pr-review
    version: "1.0"
    title: Pull Request Review
    summary: Automated PR review
    definitionFile: case-defs/pr-review.yaml              # NEW — classpath/filesystem path
    # definitionPayload is populated by the compiler from definitionFile
```

Channels and trust sections are unchanged from #2.

## Package Layout

**api/ additions:**

```
io.casehub.ops.api.deployment/
├── ProviderConfig.java              # NEW — typed marker + opaque payload
├── NodeDriftChecker.java            # NEW — drift detection SPI
├── AgentNodeSpec.java               # MODIFIED — +briefing, +providerConfigs
├── CaseTypeNodeSpec.java            # MODIFIED — +definitionFile, +definitionPayload
├── ... (unchanged from #2)
```

**deployment/ additions and changes:**

```
io.casehub.ops.deployment/
├── DeploymentGoalCompiler.java          # MODIFIED — case type definition resolution
├── DeploymentActualStateAdapter.java    # REFACTORED — layered dispatch via NodeDriftChecker
├── DeploymentNodeProvisioner.java       # MODIFIED — spec hash recording
├── DeploymentGoalLoader.java            # NEW — YAML parsing, multi-file merging
├── DefinitionPayloadLoader.java           # NEW — case definition YAML parsing
├── SpecHashStore.java                   # NEW — spec hash storage for drift detection
├── DeploymentProviderConfigStore.java   # NEW — provider config storage for cross-repo consumption
├── handler/
│   ├── AgentProvisionHandler.java       # MODIFIED — briefing + providerConfig via store
│   ├── CaseTypeProvisionHandler.java    # MODIFIED — full CaseDefinition from payload
│   ├── TrustPolicyProvisionHandler.java # MOVED from root package
│   └── ... (unchanged)
└── drift/
    ├── AgentDriftChecker.java           # NEW — extracted from ActualStateAdapter
    ├── ChannelDriftChecker.java         # NEW — extracted from ActualStateAdapter
    ├── CaseTypeDriftChecker.java        # NEW — uses isRegistered() from handler
    └── TrustPolicyDriftChecker.java     # NEW — compares policy fields
```

## Dependencies

**No new Maven dependencies for deployment/pom.xml.** All needed artifacts are already present from #2. Jackson for YAML parsing is transitive via existing deps (verify during implementation — if not, add `jackson-dataformat-yaml` as `provided`).

`NodeDriftChecker` lives in `casehub-ops-api` (this repo) — no cross-repo SPI dependency. The TransitionPlanner DRIFTED fix in casehub-desiredstate is a prerequisite for drift self-healing (see Companion Issues table).

## Testing

All tests remain plain JUnit + AssertJ. No `@QuarkusTest`.

**New test classes:**

| Test class | Covers |
|---|---|
| `ProviderConfigTest` | Validation (blank providerName rejected), config map immutability |
| `AgentNodeSpecProviderConfigTest` | AgentNodeSpec with providerConfigs + briefing, round-trip through handler |
| `CaseTypeDefinitionFileTest` | CaseTypeNodeSpec with definitionFile, compiler resolution to definitionPayload, skeleton fallback |
| `DefinitionPayloadLoaderTest` | Classpath YAML parsing, filesystem fallback, null-value preservation, missing file error, malformed YAML error, deep immutability of returned map |
| `DeploymentGoalLoaderTest` | Single-file parsing, multi-file merging, empty sections |
| `SpecHashStoreTest` | Record/check/remove lifecycle, changed spec detection, nested map equality, definitionPayload hash correctness |
| `DeploymentProviderConfigStoreTest` | Store/retrieve/remove, empty default, immutability |
| `AgentDriftCheckerTest` | Agent PRESENT/ABSENT/DRIFTED via AgentRegistry (extracted from ActualStateAdapterTest) |
| `ChannelDriftCheckerTest` | Channel mutable field comparison (extracted from ActualStateAdapterTest) |
| `CaseTypeDriftCheckerTest` | PRESENT via `isRegistered()`, ABSENT when not in map |
| `TrustPolicyDriftCheckerTest` | Policy field comparison against internal store |

**Modified test classes:**

| Test class | Changes |
|---|---|
| `AgentProvisionHandlerTest` | Briefing field mapping (18th param), providerConfig via DeploymentProviderConfigStore |
| `CaseTypeProvisionHandlerTest` | Full CaseDefinition construction from definitionPayload |
| `DeploymentNodeProvisionerTest` | Spec hash stored on provision, removed on deprovision |
| `DeploymentGoalCompilerTest` | definitionFile → definitionPayload resolution, enriched CaseTypeNodeSpec |
| `DeploymentActualStateAdapterTest` | Refactored to test layered dispatch: external SPI first → spec hash second |
| `DeploymentLifecycleIntegrationTest` | Extended with providerConfig, definitionFile, layered drift |
| `TrustPolicyProvisionHandlerTest` | Package import update after move to handler/ |

## Companion Issues

| Repo | Summary | Dependency |
|---|---|---|
| `casehub-desiredstate` | TransitionPlanner: treat DRIFTED like ABSENT (re-provision) | **Prerequisite** — without this, drift detection provides OTel observability but no self-healing |
| `casehub-desiredstate` | #36 — tenancyId on `readActual()` and `execute()` | Already filed, still open |
| `casehub-eidos` | Optional `casehub-eidos-desiredstate` module — `NodeDriftChecker` for agents | Follow-up |
| `casehub-qhorus` | Optional `casehub-qhorus-desiredstate` module — `NodeDriftChecker` for channels | Follow-up |
| `casehub-engine` | `CaseDefinitionRegistry.findByIdentity()` for case type drift detection | Follow-up |
| `casehub-engine` | Optional `casehub-engine-desiredstate` module — `NodeDriftChecker` for case types | Follow-up (depends on findByIdentity) |
| `claudony` | Read agent provider config from `DeploymentProviderConfigStore` | Follow-up |
| `casehub-openclaw` | Read agent provider config from `DeploymentProviderConfigStore` | Follow-up |

## PLATFORM.md Updates

After implementation, update the capability ownership table entry for `casehub-ops` deployment module to include: provider-specific agent config (`ProviderConfig`, `DeploymentProviderConfigStore`), case definition file loading (`definitionPayload` via `DefinitionPayloadLoader`), layered drift detection (`NodeDriftChecker` SPI in ops-api, `SpecHashStore`), multi-file YAML declaration support (`DeploymentGoalLoader`).

Add cross-repo dependency rows for bridge modules depending on `casehub-ops-api`.

## Review Changelog (revision 4)

| # | Change | Rationale |
|---|---|---|
| D | Dependencies section updated — removed "No prerequisite companion issues" claim; TransitionPlanner fix is a prerequisite | Text contradicted the Companion Issues table after revision 3 added the TransitionPlanner prerequisite. |
| E | Spec hash check gated on `external == NodeStatus.PRESENT` only | UNKNOWN nodes should stay UNKNOWN — promoting to DRIFTED without evidence is semantically incorrect. Code now matches the comment. |

## Review Changelog (revision 3)

| # | Change | Rationale |
|---|---|---|
| A | Added TransitionPlanner DRIFTED fix as prerequisite companion issue; documented that FaultPolicy RemoveNode+AddNode does NOT resolve drift (planner only deprovisions PRESENT, not DRIFTED); drift detection provides OTel observability in the interim | Drift detection is inert without a planner code path for DRIFTED. The correct fix is a one-line TransitionPlanner change, not a graph mutation workaround. |
| B | `Map.copyOf()` → `Collections.unmodifiableMap(new LinkedHashMap<>())` for `ProviderConfig.config`, `CaseTypeNodeSpec.definitionPayload`, and `DefinitionPayloadLoader` deep freeze | `Map.copyOf()` throws NPE on null values. YAML parsers produce null values for `null`, `~`, and empty fields. `Collections.unmodifiableMap()` preserves nulls while maintaining immutability. |
| C | Renamed `CaseDefinitionLoader` → `DefinitionPayloadLoader`; `loadDirectory()` is filesystem-only | Class returns `Map<String, Object>`, not `CaseDefinition`. Classpath directory listing is unreliable across JAR/exploded layouts. |

## Review Changelog (revision 2)

| # | Change | Rationale |
|---|---|---|
| 1 | `CaseDefinition definition` → `Map<String, Object> definitionPayload` | `CaseDefinition.hashCode()` uses only namespace+name+version — defeats SpecHashStore. Mutable JavaBean on immutable record violates value-type contract. |
| 2 | (Covered by #1) | Immutable `Map.copyOf()` eliminates mutable-type-on-record problem. |
| 3 | `NodeDriftChecker` moved from `casehub-desiredstate-api` to `casehub-ops-api` | Deployment-domain dispatch pattern, not a platform SPI. Infra uses different decomposition (`InfraBackend.readState()`). Eliminates prerequisite companion issue. |
| 4 | Layer order reversed — external SPI check first, spec hash second | First-run: no stored hashes → `hasDrifted()` returns true → everything falsely reported as DRIFTED instead of ABSENT. External truth first is architecturally correct. |
| 5 | `CaseTypeDriftChecker` uses `isRegistered()`, not UNKNOWN | Returning UNKNOWN is a regression from working `isRegistered()` check. Internal `ConcurrentHashMap` IS the registry for the current implementation. |
| 6 | `providerConfigs` defaults to `List.of()`, not nullable | Consistent with `capabilities` field pattern on the same record. |
| 7 | Added `DeploymentProviderConfigStore` bean | Follows `DeploymentTrustRoutingPolicyProvider` pattern — dedicated bean for externally-consumable deployment data. |
| 8 | Specified classpath-first, filesystem-fallback resolution | `DeploymentGoalLoader` and `DefinitionPayloadLoader` support both bundled resources (dev/prod) and operator-supplied files (deployment). |
| 9 | `TrustPolicyProvisionHandler` moves to `handler/` subpackage | Currently in root package; all other handlers are in `handler/`. |
| 10 | Complete `AgentNodeSpec` record signature shown | `briefing` after `dataHandlingPolicy` (matches `AgentDescriptor` order), `providerConfigs` last (deployment-specific). |
| 11 | Updated `AgentDescriptor` constructor call shown | `spec.briefing()` as 18th parameter after `context.tenancyId()`. |
