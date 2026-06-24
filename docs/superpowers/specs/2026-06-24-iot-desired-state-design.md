# IoT Desired State Domain — casehub-ops/iot

**Issue:** casehubio/casehub-ops#4
**Date:** 2026-06-24
**Module:** `casehub-ops-iot` (integration tier)

---

## What this implements

The IoT desired-state domain: SPI implementations that bridge `casehub-desiredstate-api` (generic reconciliation runtime) to `casehub-iot-api` (typed IoT device abstraction). Declares what devices should exist and how they should be configured. The reconciliation loop converges actual state to desired state continuously.

---

## Two node types

From the issue spec:

| Node type | NodeType string | requiresHuman | Provisioning mechanism |
|---|---|---|---|
| Physical device | `physical-device` | `true` | Runtime creates WorkItem (human installs device) |
| Device configuration | `device-config` | `false` | `DeviceProvider.dispatch(DeviceCommand)` |

**Dependency chain:** device-config depends on its physical-device node. Configuration can't be applied until the device is physically installed.

---

## Runtime behaviour (verified from bytecode)

The casehub-desiredstate runtime has one known limitation that affects this module:

**requiresHuman=true → Skipped (no WorkItem)**

`SimpleTransitionExecutor.executeProvision()` returns `StepOutcome.Skipped("requires human")` for human nodes. No WorkItem is created. Filed as casehubio/casehub-desiredstate#43.

**DRIFTED handling (resolved):** `TransitionPlanner.plan()` now handles DRIFTED — the provision switch covers `ABSENT, UNKNOWN, DRIFTED → true; PRESENT → false`. DRIFTED nodes are added to the transition plan and dispatched to `NodeProvisioner.provision()`. Config drift self-healing works today. (casehubio/casehub-desiredstate#38 — fix landed in the 0.2-SNAPSHOT artifact.)

**Forward compatibility:** All SPI implementations in this module are correct for the eventual runtime behaviour. When #43 ships, physical device WorkItem tracking works automatically. Zero code changes required in this module.

| Scenario | Today | After #43 |
|---|---|---|
| Physical device ABSENT | Skipped (no action) | WorkItem created |
| Physical device PRESENT | No action | No action |
| Config DRIFTED | NodeProvisioner dispatches DeviceCommand | Same |
| Config ABSENT (device missing) | provision() → Failed | Same |
| Config PRESENT | No action | No action |

---

## Domain model (API types)

**Package:** `io.casehub.ops.api.iot` (in `casehub-ops-api`)

### Sealed NodeSpec hierarchy

```java
public sealed interface IoTNodeSpec extends NodeSpec
    permits PhysicalDeviceSpec, DeviceConfigSpec {
    String deviceId();
    DeviceClass deviceClass();
}
```

### PhysicalDeviceSpec

Represents a physical device that should exist.

```java
public record PhysicalDeviceSpec(
    String deviceId,
    DeviceClass deviceClass,
    String label
) implements IoTNodeSpec {
    public PhysicalDeviceSpec {
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        Objects.requireNonNull(label, "label required");
    }
}
```

- Identity only — no configuration (that belongs in DeviceConfigSpec)
- Used with `requiresHuman=true` on DesiredNode
- ActualStateAdapter checks DeviceRegistry: found → PRESENT, not found → ABSENT, wrong class → DRIFTED

### DeviceConfigSpec

Represents desired configuration for a device.

```java
public record DeviceConfigSpec(
    String deviceId,
    DeviceClass deviceClass,
    Map<String, Object> desiredCapabilities
) implements IoTNodeSpec {
    public DeviceConfigSpec {
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        if (desiredCapabilities.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "null capability values not permitted — remove the key instead");
        }
        desiredCapabilities = Map.copyOf(desiredCapabilities);
    }
}
```

- Used with `requiresHuman=false` on DesiredNode
- `desiredCapabilities` keys match `DeviceEntity.capabilities()` key names exactly
- Only configurable capabilities: `isOn`, `targetTemperature`, `mode`, `brightness`, `colorTemp`, `isLocked`, `position`, `volume`, `speed`
- Read-only capabilities (`available`, `currentTemperature`, `power`, `energy`, `isPresent`, `lastSeen`, `isMoving`) never appear in desired spec
- Null values are rejected — a capability with null value is meaningless; omit the key instead

### NodeId convention

When `physical=true` (two nodes):
- Physical node: `NodeId.of(deviceId)` — e.g., `"thermostat-lr1"`
- Config node: `NodeId.of(deviceId + "-config")` — e.g., `"thermostat-lr1-config"`

When `physical=false` (config-only, single node):
- Config node: `NodeId.of(deviceId)` — bare deviceId, no suffix

The `-config` suffix exists solely to disambiguate from the physical node. When there's no physical node, the config node owns the bare deviceId. This makes `dependsOn` references uniform — always use the bare deviceId regardless of the target's `physical` flag.

---

## Goal specification

**Package:** `io.casehub.ops.iot` (in `casehub-ops-iot` — goal types are domain-private)

### IoTGoals

```java
public record IoTGoals(
    String tenancyId,
    List<IoTDeviceGoal> devices
) {
    public IoTGoals {
        Objects.requireNonNull(tenancyId, "tenancyId required");
        devices = List.copyOf(devices);
    }
}
```

### IoTDeviceGoal

```java
public record IoTDeviceGoal(
    String deviceId,
    DeviceClass deviceClass,
    String label,
    Boolean physical,
    Map<String, Object> config,
    List<String> dependsOn
) {
    public IoTDeviceGoal {
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        Objects.requireNonNull(label, "label required");
        physical = physical != null ? physical : true;
        if (config != null && config.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "null config values not permitted — remove the key instead");
        }
        config = config != null ? Map.copyOf(config) : Map.of();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
```

- **Boxed `Boolean physical`** — primitive `boolean` receives `false` from Jackson when the YAML key is absent, indistinguishable from explicit `physical: false`. Boxed `Boolean` gives `null` for absent keys, which the compact constructor maps to `true`. This is the only approach that works with record compact constructors without custom deserializer machinery.
- `physical: false` creates config-only node (device managed externally)

### YAML format

```yaml
tenancyId: home-1
devices:
  - deviceId: zigbee-hub
    deviceClass: SWITCH
    label: Zigbee Hub
    config:
      isOn: true

  - deviceId: thermostat-lr1
    deviceClass: THERMOSTAT
    label: Living Room Thermostat
    dependsOn: [zigbee-hub]
    config:
      targetTemperature:
        value: 22
        unit: CELSIUS
      mode: HEAT

  - deviceId: porch-light
    deviceClass: LIGHT
    label: Porch Light
    physical: false
    config:
      isOn: true
      brightness: 80

  - deviceId: front-door-lock
    deviceClass: LOCK
    label: Front Door Lock
    config:
      isLocked: true
```

### IoTGoalLoader

`@ApplicationScoped`. Jackson YAML mapper with JavaTimeModule.

- `load(String path)` — single file (classpath-first, filesystem-fallback)
- `loadDirectory(String directoryPath)` — all `.yaml`/`.yml` files
- `merge(IoTGoals... fragments)` — merge device lists, reject duplicate deviceIds

---

## SPI implementations

All in package `io.casehub.ops.iot`, all `@ApplicationScoped`.

### IoTGoalCompiler

Implements `GoalCompiler<IoTGoals>`.

**compile(IoTGoals goals, DesiredStateGraphFactory factory):**

1. Build lookup `Map<String, IoTDeviceGoal>` by deviceId
2. For each device goal:
   - If `physical=true`:
     - Physical node: `DesiredNode(NodeId.of(deviceId), NodeType.of("physical-device"), PhysicalDeviceSpec, requiresHuman=true)`
     - Config node: `DesiredNode(NodeId.of(deviceId + "-config"), NodeType.of("device-config"), DeviceConfigSpec, requiresHuman=false)`
     - Dependency: `Dependency(deviceId-config, deviceId)`
   - If `physical=false`:
     - Config node: `DesiredNode(NodeId.of(deviceId), NodeType.of("device-config"), DeviceConfigSpec, requiresHuman=false)` — bare deviceId, no suffix
3. For each `dependsOn` entry:
   - `dependsOn` always references bare deviceIds — resolves to `NodeId.of(depDeviceId)` regardless of the target's `physical` flag (physical nodes and config-only nodes both use bare deviceId as their primary)
   - Create `Dependency(thisDevice-primaryNodeId, NodeId.of(depDeviceId))`
   - Primary nodeId of this device: `NodeId.of(deviceId)` when `physical=true` (physical node), `NodeId.of(deviceId)` when `physical=false` (config-only node) — always the bare deviceId
4. Return `factory.of(allNodes, allDependencies)`

### IoTActualStateAdapter

Implements `ActualStateAdapter`. Injected: `DeviceRegistry`.

**readActual(DesiredStateGraph desired) → ActualState:**

For each node, extract `IoTNodeSpec`, look up `DeviceRegistry.findById(spec.deviceId())`.

**PhysicalDeviceSpec:**

| Registry result | NodeStatus |
|---|---|
| Found, class matches | PRESENT |
| Found, class mismatch | DRIFTED |
| Not found | ABSENT |

**DeviceConfigSpec:**

| Registry result | NodeStatus |
|---|---|
| Not found | ABSENT |
| Found, normalized capabilities match desired | PRESENT |
| Found, capabilities differ | DRIFTED |

**Capability comparison** uses `CapabilityNormalizer` — converts both actual and desired capabilities to a canonical form for comparison. The critical boundary is between YAML-parsed types (Integer, String, LinkedHashMap) and casehub-iot types (Temperature with BigDecimal, ThermostatMode enum).

Normalization rules (applied to BOTH sides):
- `Temperature(BigDecimal, TemperatureUnit)` → `Map.of("value", BigDecimal, "unit", "CELSIUS")`
- All numeric values → `BigDecimal.stripTrailingZeros()` — `BigDecimal.equals()` is scale-sensitive (`new BigDecimal("22.0").equals(new BigDecimal("22"))` is false). `stripTrailingZeros()` canonicalizes scale so `22`, `22.0`, and `22.00` all compare equal.
- **Recursion into nested Maps** — normalization recurses into `Map<String, Object>` values. This is critical for `targetTemperature`: the actual side normalizes `Temperature` to `Map.of("value", BigDecimal(22), "unit", "CELSIUS")`, and the desired side has a YAML-parsed `LinkedHashMap` with `Integer(22)` inside it. Without recursion, `BigDecimal(22).equals(Integer(22))` → false → false drift.
- Enum values (ThermostatMode, etc.) → `name()` String
- Boolean, String → pass through

The normalizer runs on BOTH the actual capabilities (from `DeviceEntity.capabilities()`) AND the desired capabilities (from `DeviceConfigSpec.desiredCapabilities()`). This eliminates type mismatches at comparison time.

Only keys present in the desired spec are compared. Read-only capabilities ignored.

### IoTNodeProvisioner

Implements `NodeProvisioner` (blocking). Injected: `DeviceRegistry`, `@Any Instance<DeviceProvider>`.

Builds `Map<String, DeviceProvider>` by providerId at construction.

**provision(DesiredNode node, ProvisionContext context):**

```java
return switch (node.spec()) {
    case PhysicalDeviceSpec s -> new ProvisionResult.Failed(
        "physical devices cannot be auto-provisioned");
    case DeviceConfigSpec s -> provisionConfig(s);
};
```

**provisionConfig(DeviceConfigSpec spec):**

1. `DeviceRegistry.findById(spec.deviceId())` — if absent → `Failed("device not present")`
2. Resolve provider: `device.providerId()` → look up in `Map<String, DeviceProvider>`. If no provider registered for this providerId → `Failed("no provider for '" + providerId + "'")`
3. Normalize actual capabilities, compute diff against `spec.desiredCapabilities()`
4. Create `CommandContext("casehub-ops-iot-provisioner", UUID.randomUUID().toString())`
5. For each drifted capability: `CapabilityCommandMapper.toCommand(deviceId, key, value, ctx)` → `DeviceCommand`
6. Dispatch via `provider.dispatch(command).await().indefinitely()`
7. All `SENT` → `Success()`. Any `FAILED`/`TIMEOUT` → `Failed(reason)`

**deprovision:**

- PhysicalDeviceSpec → `Failed("physical devices cannot be auto-deprovisioned")`
- DeviceConfigSpec → `Success()` (stop managing, leave device as-is)

### CapabilityCommandMapper

Utility class. Static method:

```java
static DeviceCommand toCommand(String deviceId, String capabilityKey,
                                Object desiredValue, CommandContext ctx)
```

**CommandContext** — static inner record of `CapabilityCommandMapper`. Created once per `provision()` call by the provisioner:

```java
record CommandContext(String dispatchedBy, String correlationId) {}
```

- `dispatchedBy`: `"casehub-ops-iot-provisioner"` — constant identifying the reconciliation provisioner for audit trail
- `correlationId`: UUID generated per `provision()` call — ties commands to a specific reconciliation cycle for OTel tracing

**Command mapping table** (verified against `DeviceCommand` bytecode):

| Capability | Value | DeviceCommand |
|---|---|---|
| `isOn` | `true` | `DeviceCommand.turnOn(deviceId, Map.of(), ctx.dispatchedBy(), ctx.correlationId())` |
| `isOn` | `false` | `DeviceCommand.turnOff(deviceId, ctx.dispatchedBy(), ctx.correlationId())` |
| `targetTemperature` | `{value, unit}` | `DeviceCommand.setTemperature(deviceId, new Temperature(toBigDecimal(value), TemperatureUnit.valueOf(unit)), ctx.dispatchedBy(), ctx.correlationId())` |
| `isLocked` | `true` | `DeviceCommand.lock(deviceId, ctx.dispatchedBy(), ctx.correlationId())` |
| `isLocked` | `false` | `DeviceCommand.unlock(deviceId, ctx.dispatchedBy(), ctx.correlationId())` |
| `position` | int | `DeviceCommand.setPosition(deviceId, intValue, ctx.dispatchedBy(), ctx.correlationId())` |
| `volume` | int | `DeviceCommand.setVolume(deviceId, intValue, ctx.dispatchedBy(), ctx.correlationId())` |
| Other | any | `new DeviceCommand(deviceId, "set_" + key, Map.of(key, value), ctx.dispatchedBy(), ctx.correlationId())` |

**Type reconstruction for `targetTemperature`:** YAML parses `{value: 22, unit: CELSIUS}` as `Map<String, Object>` with Integer value. The mapper must reconstruct `Temperature(BigDecimal.valueOf(22), TemperatureUnit.CELSIUS)` — extracting `value` as Number → `BigDecimal`, `unit` as String → `TemperatureUnit.valueOf()`.

**`turnOn()` parameters:** The `turnOn()` factory requires a `Map<String, Object> parameters` argument (unlike `turnOff()` which does not). For a simple on/off toggle, pass `Map.of()`. Device-specific parameters (e.g., transition time for lights) are not supported in this version.

### IoTFaultPolicy

Implements `FaultPolicy`.

**onFault(FaultEvent, DesiredStateGraph) → List\<GraphMutation\>:**

Returns empty list for all fault types. No-op stub consistent with infra/compliance/deployment modules. Real fault responses deferred to casehubio/casehub-ops#10 — needs operational feedback.

### IoTEventSource

Implements `EventSource`. Bridges CDI events to `Multi<StateEvent>`.

Hot stream with `BackPressureStrategy.BUFFER`. Two CDI observers:

**@ObservesAsync StateChangeEvent:**
- `before == null` (new device) → `StateEvent(NodeId.of(deviceId), PRESENT, "device discovered")`
- Capability changes → `StateEvent(NodeId.of(deviceId + "-config"), DRIFTED, "capabilities changed")`
- `available` goes false → `StateEvent(NodeId.of(deviceId), DRIFTED, "device offline")`

**@ObservesAsync ProviderStatusEvent:**

The EventSource maintains a `Map<String, Set<String>>` of providerId → deviceIds, populated from observed StateChangeEvents. When `event.currentStatus() == DISCONNECTED`, it emits `StateEvent(NodeId.of(deviceId), UNKNOWN, "provider disconnected")` for each known device from that provider. This maps provider-level outages to actual graph nodes.

If no devices have been observed for the disconnected provider (EventSource started after devices were already registered), the event is logged but no StateEvent is emitted — the next periodic resync (default 5 min) will detect the issue via ActualStateAdapter.

**Reconciliation trigger model:** The ReconciliationLoop uses events as triggers (debounced batch → full reconcile()). Individual event NodeIds/statuses are for tracing — the reconcile() runs a full readActual() regardless of what triggered it.

---

## Tenancy propagation

CLAUDE.md mandates: "tenancyId propagated through all calls — bind in repository/adapter layer only."

**How tenancyId flows in the IoT domain:**

1. `IoTGoals.tenancyId()` — set in the YAML, passed to `ReconciliationLoop.start(tenancyId, graph)`
2. `ProvisionContext.tenancyId()` — **currently hardcoded to `"default"` in `SimpleTransitionExecutor`** (`private static final String DEFAULT_TENANCY = "default"`). The runtime does not propagate the tenancyId from `ReconciliationLoop.start()` to the executor's `ProvisionContext`. This is a runtime limitation, not an IoT design concern — when the runtime fixes the propagation, the provisioner will receive the correct tenancyId automatically.
3. `ActualStateAdapter.readActual(DesiredStateGraph)` — no tenancyId parameter in the SPI contract

**Why no explicit tenancy filter in the adapter or provisioner:**

`DeviceRegistry.findById(deviceId)` returns a device by its globally unique deviceId. Tenancy scoping is implicit: the goal YAML for tenant A contains only tenant A's deviceIds. The adapter queries by deviceId, not by tenant. `DeviceCommand` dispatches by `targetDeviceId` only — no tenancyId field.

If two tenants claim the same deviceId, that's a conflict at the goal-validation level (IoTGoalLoader should reject it), not a runtime filtering concern.

`DeviceEntity.tenancyId()` is available for logging, tracing, and audit correlation — the provisioner could log it when dispatching commands. But it does not affect lookup or dispatch logic.

---

## Why no SpecHashStore

ARC42STORIES.MD §8 documents that deployment and compliance modules use `ConcurrentHashMap<NodeId, Integer>` to detect when a node's desired spec changes between reconciliation cycles — even if actual state coincidentally matches the old spec.

**IoT does not need this.** The reason is structural:

- **Deployment:** `AgentDriftChecker` queries `AgentRegistry`, which may not expose all agent configuration fields. A spec change from `count: 2` to `count: 3` might not be visible through the registry query. The hash catches what the query misses.
- **Compliance:** Evidence results are point-in-time snapshots. A spec change (e.g., `evidenceMaxAgeDays: 30` → `60`) doesn't change the evidence itself — the hash detects the intent change.
- **IoT:** `DeviceEntity.capabilities()` returns the **full configurable state** of the device. The ActualStateAdapter compares desired capabilities directly against actual capabilities. If the YAML changes `targetTemperature` from 22 to 24 and the device is still at 22, the adapter reports DRIFTED — no hash needed.

The hash is a secondary detection layer for SPIs that can't fully observe actual state. IoT's DeviceRegistry provides full observability.

---

## Testing strategy

### Dependencies to add

**Build prerequisite:** `casehub-iot` must be built at 0.2-SNAPSHOT (`mvn -C /path/to/casehub-iot install`). The `casehub-iot-testing` module exists in source (`casehub-iot/testing/`) and was published at 0.1-SNAPSHOT but the 0.2-SNAPSHOT jar is not yet deployed.

**BOM addition** — add to `casehub-ops-parent/pom.xml` `<dependencyManagement>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <version>${version.io.casehub}</version>
    <scope>test</scope>
</dependency>
```

**Module dependency** — add to `iot/pom.xml`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <scope>test</scope>
</dependency>
```

**Classes provided by `casehub-iot-testing`** (verified in `casehub-iot/testing/src/main/java/`):
- `MockDeviceProvider` — in-memory DeviceProvider with dispatch recording
- `MockDeviceRegistry` — in-memory DeviceRegistry
- `Fixtures` — pre-built device instances (`standardHome()`, individual device factories)
- `StateChangeEventPublisher` — CDI bean for firing test events
- `DeviceFixtureLoader` — YAML-based device fixture loading

### Unit tests

| Class | Test count | Key scenarios |
|---|---|---|
| IoTGoalCompiler | 5 | physical=true (2 nodes + dep), physical=false (1 node), dependsOn edges, empty list, duplicate deviceId rejection |
| IoTActualStateAdapter | 5 | device present + config matches, config drifted, device absent, wrong device class, provider disconnected |
| IoTNodeProvisioner | 7 | config provision (single drift, multi drift, device absent, command failed), physical provision (returns Failed), deprovision config, deprovision physical |
| CapabilityCommandMapper | 9 | isOn true/false, targetTemperature (Integer→BigDecimal→Temperature reconstruction), isLocked true/false, position, volume, generic fallback, dispatchedBy/correlationId propagation, turnOn Map.of() parameters |
| CapabilityNormalizer | 8 | Temperature→map with BigDecimal, enum→string, Integer→BigDecimal, Double→BigDecimal, boolean pass through, stripTrailingZeros (22.0 equals 22), nested Map recursion (Integer inside Map→BigDecimal), nested Map with mixed types |
| IoTGoalLoader | 3 | single YAML, directory, merge (duplicate rejection) |
| IoTFaultPolicy | 1 | returns empty list |
| IoTEventSource | 5 | capability change, new device, device offline, provider disconnect (emits for known devices), provider disconnect (no known devices — no emit) |

### Integration test

Full reconciliation cycle with MockDeviceProvider and Fixtures:

1. Create IoTGoals with 3 devices (thermostat, light, lock)
2. Compile → DesiredStateGraph
3. MockDeviceProvider has 2 of 3 present (thermostat missing)
4. Read actual → thermostat ABSENT, light PRESENT, lock PRESENT
5. Drift light brightness → re-read → light DRIFTED
6. Plan + execute → provisioner dispatches brightness command
7. Assert MockDeviceProvider.dispatchedCommands() correct

### Test constructors

All SPI implementations provide package-private constructors accepting concrete dependencies (no CDI container needed for unit tests).

---

## Package structure

**casehub-ops-api** (`io.casehub.ops.api.iot`):
- `IoTNodeSpec` (sealed interface)
- `PhysicalDeviceSpec` (record)
- `DeviceConfigSpec` (record)

**casehub-ops-iot** (`io.casehub.ops.iot`):
- `IoTGoals` (record)
- `IoTDeviceGoal` (record)
- `IoTGoalCompiler`
- `IoTGoalLoader`
- `IoTActualStateAdapter`
- `IoTNodeProvisioner`
- `IoTFaultPolicy`
- `IoTEventSource`
- `CapabilityCommandMapper` (utility)
- `CapabilityNormalizer` (utility)

---

## Filed issues

| Repo | Issue | Purpose |
|---|---|---|
| casehubio/casehub-desiredstate | #43 | WorkItem creation for requiresHuman=true nodes |
| casehubio/parent | #309 | PLATFORM.md dependency row for casehub-iot-api → casehub-ops/iot |
| casehubio/casehub-ops | #10 | IoTFaultPolicy domain-specific fault responses (deferred) |

---

## Acceptance criteria

- [ ] `casehub-ops-iot` compiles with casehub-iot-api on classpath
- [ ] `IoTNodeSpec` sealed interface in `casehub-ops-api` with `PhysicalDeviceSpec` and `DeviceConfigSpec`
- [ ] `IoTGoalCompiler.compile()` produces correct graph: physical nodes (requiresHuman=true), config nodes (requiresHuman=false), dependency edges
- [ ] `IoTActualStateAdapter.readActual()` reports correct NodeStatus for all scenarios (present, absent, drifted, wrong class)
- [ ] `IoTNodeProvisioner.provision()` dispatches correct DeviceCommands for drifted capabilities
- [ ] `CapabilityNormalizer` handles Temperature, enums, and primitives
- [ ] `CapabilityCommandMapper` maps all standard capability keys to correct DeviceCommands
- [ ] `IoTEventSource` bridges StateChangeEvent and ProviderStatusEvent to Multi<StateEvent>
- [ ] `IoTGoalLoader` loads single YAML and directory of YAML files
- [ ] All unit tests pass (43 tests across 8 classes)
- [ ] Integration test: full reconciliation cycle with MockDeviceProvider
