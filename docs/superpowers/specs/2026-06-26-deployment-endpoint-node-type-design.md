# Deployment Module Design — Endpoint Node Type

**Issue:** casehubio/casehub-ops#6
**Date:** 2026-06-26
**Status:** Approved (revision 2 — post-review)
**Extends:** [#2 spec](2026-06-16-deployment-agent-topology-design.md), [#7 spec](2026-06-17-deployment-app-level-topology-design.md)

## Problem

The deployment module declares four node types: agents, channels, case types, trust policies. But a CaseHub deployment's topology also includes its external connectivity — Kafka topics, MCP tool servers, gRPC services, HTTP endpoints, Camel routes. These are `EndpointDescriptor` entries in the platform's `EndpointRegistry`, and the platform's stream modules (`streams-kafka`, `streams-camel`, `streams-poll`) already react to registered endpoints. The deployment module cannot declare or manage them.

Without endpoint nodes, the deployment YAML describes *who does the work* but not *what infrastructure the deployment connects to*. The full pipeline — endpoints → streams → CloudEvent → RAS → startCase() — is disconnected from desired-state management.

## Solution

Add `EndpointNodeSpec` as a fifth node type in the deployment module's sealed hierarchy. Endpoints are declared in the deployment YAML, compiled into the `DesiredStateGraph`, provisioned via `EndpointRegistry.register()`, and drift-detected via `EndpointRegistry.resolve()`. The existing reconciliation loop handles continuous endpoint management automatically.

## Scope

This spec covers endpoint node declaration, provisioning, and drift detection within casehub-ops only. It does not cover:
- A real `EndpointRegistry` implementation — the platform provides one in `casehub-platform-endpoints-memory` (not on the ops classpath; verified absent via `ide_find_class`). `NoOpEndpointRegistry @DefaultBean` is the only implementation available to ops at compile time.
- `EndpointRegistered` CDI event firing (registry implementation responsibility — see Deferred Concerns)
- RAS situation registration as a node type (deferred — requires RAS declarative API, tracked in #11)

## Key Design Decisions

**Single protocol-agnostic EndpointNodeSpec.** One record with `EndpointProtocol` as a field, not a sealed hierarchy of per-protocol subtypes. `EndpointDescriptor` already uses `Map<String, String>` for protocol-specific properties — a typed subtype hierarchy would add a parse/unparse layer (typed fields ↔ map) with no architectural benefit. The deployment module's compilation is trivial (wrap and extract dependsOn edges). Per-protocol subtypes would contradict this.

**Protocol-aware construction validation.** The `EndpointNodeSpec` compact constructor validates cross-module required properties using `EndpointPropertyKeys` constants: `EndpointPropertyKeys.TOPIC` for KAFKA, `EndpointPropertyKeys.URL` for HTTP/GRPC. These are the keys that stream modules read. Deployment-local keys (e.g. `bootstrap.servers`) are not validated — per `spi-property-keys-cross-module-only` protocol, only keys whose values cross module boundaries warrant platform-level validation.

**Protocol validation covers all seven `EndpointProtocol` values:**

| Protocol | Required property | Rationale |
|---|---|---|
| `KAFKA` | `EndpointPropertyKeys.TOPIC` | `streams-kafka` reads this to subscribe |
| `HTTP` | `EndpointPropertyKeys.URL` | `streams-poll` reads this to poll |
| `GRPC` | `EndpointPropertyKeys.URL` | gRPC endpoint resolution |
| `AMQP` | *(none)* | Address configured externally via SmallRye reactive messaging |
| `MCP` | *(none)* | Server discovery is provider-specific |
| `CAMEL` | *(none)* | Route URI is deployment-local configuration |
| `QHORUS` | *(none)* | Internal platform protocol — channel name is deployment-local |

`EndpointPropertyKeys.STREAM_EVENT_TYPE` and `EndpointPropertyKeys.STREAM_DATA_CONTENT_TYPE` are optional cross-module properties. Stream modules use them to set CloudEvent metadata (`type` and `datacontenttype` fields). If absent, stream modules use defaults — missing them degrades event metadata quality but does not prevent stream activation. Not validated at construction.

**`toDescriptor(tenancyId)` as the single conversion point.** `EndpointNodeSpec.toDescriptor(String tenancyId)` constructs an `EndpointDescriptor`. This follows the `AgentNodeSpec.toDescriptor()` precedent and serves as the single field-mapping fix point for both the provisioner (`register(spec.toDescriptor(...))`) and the drift checker (`actual.equals(spec.toDescriptor(...))`). Since `EndpointDescriptor` is a record, `equals()` is structurally correct — no separate comparator needed (unlike `AgentDescriptor` which requires `AgentDescriptorComparator` for field-level drift reporting).

**`path` as nodeId.** `EndpointDescriptor` is keyed by `(Path, tenancyId)`. Since `tenancyId` comes from `ProvisionContext`, the path string is the natural node identifier.

**No EndpointRegistered event responsibility.** The provisioner calls `EndpointRegistry.register()`. Whether the registry fires `EndpointRegistered` CDI events is the registry implementation's concern. `streams-camel` observes these events for dynamic route setup — the deployment module doesn't need to know about this.

**NoOp registry produces perpetual ABSENT.** If `NoOpEndpointRegistry` is active (no real registry on classpath), `register()` silently does nothing and `resolve()` returns empty. Endpoints stay perpetually ABSENT in reconciliation — each cycle generates a provision step that silently succeeds without effect. This is operationally noisy (5 endpoints × N cycles = phantom provisions in OTel traces), but is consistent with how all other handlers behave: `AgentProvisionHandler` calls `agentRegistry.register()` and returns `Success()` without post-provision verification; `CaseTypeProvisionHandler` does the same. Adding verification only for endpoints would be inconsistent. Deployments declaring endpoint nodes require a real `EndpointRegistry` implementation on the classpath (e.g. `casehub-platform-endpoints-memory`).

## API Type Changes (casehub-ops-api)

### EndpointNodeSpec — new record

```java
package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EndpointNodeSpec(
        String path,
        EndpointType type,
        EndpointProtocol protocol,
        Map<String, String> properties,
        String credentialRef,
        Set<EndpointCapability> capabilities
) implements DeploymentNodeSpec {

    public EndpointNodeSpec {
        if (path == null || path.isBlank())
            throw new IllegalArgumentException("path is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(protocol, "protocol is required");
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
        switch (protocol) {
            case KAFKA -> requireProperty(properties, EndpointPropertyKeys.TOPIC, "KAFKA");
            case HTTP, GRPC -> requireProperty(properties, EndpointPropertyKeys.URL, "HTTP/GRPC");
            default -> {}
        }
    }

    @Override
    public String nodeId() { return path; }

    @Override
    public String nodeType() { return "endpoint"; }

    public EndpointDescriptor toDescriptor(String tenancyId) {
        return new EndpointDescriptor(
                Path.parse(path), tenancyId, type, protocol,
                properties, credentialRef, capabilities);
    }

    private static void requireProperty(Map<String, String> props, String key, String context) {
        if (!props.containsKey(key) || props.get(key) == null || props.get(key).isBlank()) {
            throw new IllegalArgumentException(context + " endpoint requires '" + key + "' property");
        }
    }
}
```

### DeploymentNodeSpec — updated sealed permits

```java
public sealed interface DeploymentNodeSpec extends NodeSpec permits
        AgentNodeSpec, ChannelNodeSpec, CaseTypeNodeSpec, TrustPolicyNodeSpec, EndpointNodeSpec {
    String nodeId();
    String nodeType();
}
```

### DeploymentGoals — new endpoints field

```java
public record DeploymentGoals(
        List<GoalEntry<AgentNodeSpec>> agents,
        List<GoalEntry<ChannelNodeSpec>> channels,
        List<GoalEntry<CaseTypeNodeSpec>> caseTypes,
        List<GoalEntry<TrustPolicyNodeSpec>> trust,
        List<GoalEntry<EndpointNodeSpec>> endpoints
) {
    public DeploymentGoals {
        agents = agents != null ? List.copyOf(agents) : List.of();
        channels = channels != null ? List.copyOf(channels) : List.of();
        caseTypes = caseTypes != null ? List.copyOf(caseTypes) : List.of();
        trust = trust != null ? List.copyOf(trust) : List.of();
        endpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
    }
}
```

Breaking constructor change — all callers update mechanically.

## Architecture

### GoalCompiler — one new line

```java
@Override
public DesiredStateGraph compile(DeploymentGoals goals, DesiredStateGraphFactory factory) {
    List<DesiredNode> nodes = new ArrayList<>();
    List<Dependency> dependencies = new ArrayList<>();

    compileEntries(goals.agents(), nodes, dependencies);
    compileEntries(goals.channels(), nodes, dependencies);
    compileEntries(resolveCaseTypes(goals.caseTypes()), nodes, dependencies);
    compileEntries(goals.trust(), nodes, dependencies);
    compileEntries(goals.endpoints(), nodes, dependencies);

    return factory.of(nodes, dependencies);
}
```

No special compilation logic. The generic `compileEntries()` handles `EndpointNodeSpec` like all other types. `requiresHuman` is `false` for endpoints.

### EndpointProvisionHandler

```java
@ApplicationScoped
public class EndpointProvisionHandler {

    private final EndpointRegistry endpointRegistry;

    @Inject
    public EndpointProvisionHandler(EndpointRegistry endpointRegistry) {
        this.endpointRegistry = endpointRegistry;
    }

    public ProvisionResult provision(EndpointNodeSpec spec, ProvisionContext context) {
        endpointRegistry.register(spec.toDescriptor(context.tenancyId()));
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(EndpointNodeSpec spec, DeprovisionContext context) {
        endpointRegistry.deregister(Path.parse(spec.path()), context.tenancyId());
        return new DeprovisionResult.Success();
    }
}
```

`tenancyId` from `ProvisionContext`, not the spec. `register()` is idempotent (upsert). `toDescriptor()` is the single conversion point (defined on `EndpointNodeSpec`).

### EndpointDriftChecker

```java
@ApplicationScoped
public class EndpointDriftChecker implements NodeDriftChecker {

    private final EndpointRegistry endpointRegistry;

    @Inject
    public EndpointDriftChecker(EndpointRegistry endpointRegistry) {
        this.endpointRegistry = endpointRegistry;
    }

    @Override
    public String nodeType() { return "endpoint"; }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof EndpointNodeSpec endpoint)) return NodeStatus.UNKNOWN;

        var resolved = endpointRegistry.resolve(Path.parse(endpoint.path()), tenancyId);
        if (resolved.isEmpty()) return NodeStatus.ABSENT;

        var desired = endpoint.toDescriptor(tenancyId);
        return resolved.get().equals(desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }
}
```

Uses `toDescriptor()` + record `equals()` — structurally correct and automatically includes new fields if `EndpointDescriptor` gains them. Follows the `AgentNodeSpec.toDescriptor()` → comparator pattern, but since `EndpointDescriptor` is a record, `equals()` suffices without a dedicated comparator.

Layered with `SpecHashStore` via `DeploymentActualStateAdapter` — external check first, spec hash second.

### DeploymentNodeProvisioner — updated switch

```java
return switch (spec) {
    case AgentNodeSpec s -> agentHandler.provision(s, context);
    case ChannelNodeSpec s -> channelHandler.provision(s, context);
    case CaseTypeNodeSpec s -> caseTypeHandler.provision(s, context);
    case TrustPolicyNodeSpec s -> trustHandler.provision(s, context);
    case EndpointNodeSpec s -> endpointHandler.provision(s, context);
};
```

Same pattern for `deprovision()`. `EndpointProvisionHandler` injected via constructor.

### DeploymentGoalLoader — merge update

```java
public DeploymentGoals merge(DeploymentGoals... fragments) {
    var agents = new ArrayList<GoalEntry<AgentNodeSpec>>();
    var channels = new ArrayList<GoalEntry<ChannelNodeSpec>>();
    var caseTypes = new ArrayList<GoalEntry<CaseTypeNodeSpec>>();
    var trust = new ArrayList<GoalEntry<TrustPolicyNodeSpec>>();
    var endpoints = new ArrayList<GoalEntry<EndpointNodeSpec>>();
    for (var f : fragments) {
        agents.addAll(f.agents());
        channels.addAll(f.channels());
        caseTypes.addAll(f.caseTypes());
        trust.addAll(f.trust());
        endpoints.addAll(f.endpoints());
    }
    return new DeploymentGoals(agents, channels, caseTypes, trust, endpoints);
}
```

## YAML Schema

All entries use the `GoalEntry<S>` wrapper: `spec:` carries the `EndpointNodeSpec` fields, `dependsOn:` carries explicit dependency edges. This is consistent with all other deployment YAML sections (agents, channels, caseTypes, trust).

```yaml
endpoints:
  - spec:
      path: streams/patient-vitals
      type: SERVICE
      protocol: KAFKA
      properties:
        topic: patient.vitals
        bootstrap.servers: kafka:9092
        stream-event-type: io.casehub.clinical.vitals
        stream-data-content-type: application/json
      capabilities: [RECEIVE]
    dependsOn: []

  - spec:
      path: tools/github
      type: SERVICE
      protocol: MCP
      properties:
        serverName: github
      capabilities: [QUERY, DISPATCH]
    dependsOn: []

  - spec:
      path: services/model-inference
      type: SERVICE
      protocol: GRPC
      properties:
        url: model-inference:50051
      capabilities: [QUERY]
      credentialRef: grpc-model-creds
    dependsOn: []
```

Cross-type dependencies work naturally: an agent with `dependsOn: [tools/github]` creates a graph edge to the endpoint. The `TransitionPlanner` provisions endpoints before dependent agents.

NodeType string: `"endpoint"`.

## Package Layout

**api/ additions:**

```
io.casehub.ops.api.deployment/
├── EndpointNodeSpec.java           # NEW — endpoint record
├── DeploymentNodeSpec.java         # MODIFIED — 5th sealed permit
├── DeploymentGoals.java            # MODIFIED — +endpoints field
├── ... (unchanged)
```

**deployment/ additions and changes:**

```
io.casehub.ops.deployment/
├── DeploymentGoalCompiler.java         # MODIFIED — one new compileEntries() call
├── DeploymentNodeProvisioner.java      # MODIFIED — one new switch case + injected handler
├── DeploymentGoalLoader.java           # MODIFIED — merge includes endpoints
├── handler/
│   ├── EndpointProvisionHandler.java   # NEW
│   └── ... (unchanged)
└── drift/
    ├── EndpointDriftChecker.java       # NEW
    └── ... (unchanged)
```

## Dependencies

No new Maven dependencies. `casehub-platform-api` is already in both `api/pom.xml` and `deployment/pom.xml`, providing `EndpointRegistry`, `EndpointDescriptor`, `EndpointType`, `EndpointProtocol`, `EndpointCapability`, `EndpointPropertyKeys`, `Path`, and `EndpointRegistered`.

## Testing

All tests are plain JUnit + AssertJ. No `@QuarkusTest`.

**New test classes:**

| Test class | Covers |
|---|---|
| `EndpointNodeSpecTest` | Validation: blank path rejected, null type/protocol rejected, KAFKA without `EndpointPropertyKeys.TOPIC` rejected, HTTP/GRPC without `EndpointPropertyKeys.URL` rejected, AMQP/MCP/CAMEL/QHORUS pass without required properties, properties/capabilities immutability via `Map.copyOf()`/`Set.copyOf()`, nodeId returns path, nodeType returns "endpoint", `@JsonIgnoreProperties` present. `toDescriptor()` produces correct `EndpointDescriptor` with all fields mapped and tenancyId injected. |
| `EndpointProvisionHandlerTest` | Provision: `toDescriptor()` called with context tenancyId, result registered in EndpointRegistry. Deprovision: deregister called with correct `Path.parse()` + tenancyId. credentialRef nullable. Stub EndpointRegistry (in-memory map keyed by path+tenancyId). |
| `EndpointDriftCheckerTest` | ABSENT when not in registry. PRESENT when `resolved.equals(spec.toDescriptor(tenancyId))`. DRIFTED when any field differs (type, protocol, properties, capabilities, credentialRef). UNKNOWN for non-EndpointNodeSpec. |

**Modified test classes:**

| Test class | Changes |
|---|---|
| `DeploymentGoalCompilerTest` | New: `compilesEndpointNode()`, `compilesAllFiveTypesInOneGraph()` (was 4), `endpointDependsOnCreatesEdge()`, `crossTypeDependency()` (agent depends on endpoint). Existing: update `DeploymentGoals` constructor calls. |
| `DeploymentGoalLoaderTest` | New: endpoint section parsed from YAML, multi-file merge includes endpoints. Existing: update constructor calls. |
| `DeploymentNodeProvisionerTest` | New: endpoint dispatch case. Existing: update constructor calls. |
| `DeploymentProviderConfigStoreTest` | Constructor call updates only. |
| `DeploymentLifecycleIntegrationTest` | Extended: add endpoint node → 5-node lifecycle. Agent depends on endpoint → verify graph ordering. Endpoint drift detection (change properties → DRIFTED). New `StubEndpointRegistry` inner class. |

## Deferred Concerns

| Concern | Where to track | Rationale |
|---|---|---|
| Verify the real `EndpointRegistry` implementation (in `casehub-platform-endpoints-memory`, not on ops classpath) fires `EndpointRegistered` CDI event on `register()` | casehubio/platform#117 | `streams-camel` observes `@ObservesAsync EndpointRegistered` for dynamic Camel route setup. If the registry doesn't fire it, Camel routes won't activate from deployment-provisioned endpoints. `NoOpEndpointRegistry` should NOT fire events (it does nothing by design). |
| DetectionNodeSpec — RAS situation registration | casehubio/casehub-ops#11 | `casehub-ras` exists but has no declarative configuration API. When RAS gains one, situations become a natural deployment node type. |

## PLATFORM.md Updates

After implementation:

1. **Repository Map** — update casehub-ops description: "5 node types: agents, channels, case types, trust policies, endpoints" (remove outdated "sub-compilers for agents, streams, channels, detection, trust")
2. **Capability Ownership** — update deployment module entry to include endpoint provisioning via `EndpointRegistry`

## ARC42STORIES.MD Updates

After implementation:

1. **§3 Context and Scope** — update the `Rel(ops, platform, ...)` relationship: currently `"all: tenancy context, preferences"` — add endpoint provisioning: `"all: tenancy context, preferences; deployment: registers endpoints via EndpointRegistry"`
2. **§5 Building Block View** — if the deployment module block is enumerated, add `EndpointNodeSpec` / `EndpointProvisionHandler` / `EndpointDriftChecker` to the component list

## Review Changelog (revision 2)

| # | Change | Rationale |
|---|---|---|
| 1 | YAML schema: added `spec:` / `dependsOn:` wrapper on all endpoint entries | `GoalEntry<S>` deserialization requires this structure — verified against `topology.yaml` and `DeploymentGoalLoaderTest` |
| 2 | Replaced `InMemoryEndpointRegistry` references with "the real `EndpointRegistry` implementation (in `casehub-platform-endpoints-memory`, not on ops classpath)" | `ide_find_class` returns zero results for `InMemoryEndpointRegistry` in `project_and_libraries` scope. Only `NoOpEndpointRegistry @DefaultBean` is on the ops classpath. |
| 3 | Replaced hardcoded `"topic"` / `"url"` strings with `EndpointPropertyKeys.TOPIC` / `EndpointPropertyKeys.URL` | Cross-module key constants exist to prevent silent drift between producer (deployment) and consumer (stream modules). Using string literals defeats their purpose. |
| 4 | Added `@JsonIgnoreProperties(ignoreUnknown = true)` to `EndpointNodeSpec` | All four existing `DeploymentNodeSpec` records have this annotation. Consistency — unknown YAML properties should not fail deserialization. |
| 5 | Replaced field-by-field drift comparison with `toDescriptor(tenancyId)` + record `equals()` | Follows `AgentNodeSpec.toDescriptor()` precedent. Eliminates fragility: new `EndpointDescriptor` fields are automatically compared. Simplifies both handler (one-liner register) and checker (one-liner comparison). |
| 6 | Expanded NoOp registry discussion — documented as operational concern, no code mitigation | Consistent with all other handlers (none do post-provision verification). Perpetual ABSENT is the correct diagnostic signal for missing registry. Documented that real registry is required. |
| 7 | Listed all 7 `EndpointProtocol` values with validation status in a table | QHORUS was undiscussed. All seven now explicitly documented. |
| 8 | Documented `stream-event-type` and `stream-data-content-type` as optional cross-module properties | These are in `EndpointPropertyKeys` and used by stream modules for CloudEvent metadata. Optional — absence degrades metadata quality but doesn't prevent stream activation. |
| 9 | Added ARC42STORIES.MD §3 update requirement | New dependency relationship: deployment module now uses `EndpointRegistry` from platform-api, beyond the existing "tenancy context, preferences" relationship. |
