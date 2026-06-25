# IoT Desired State Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement IoT desired-state domain — physical + logical device provisioning bridging `casehub-desiredstate-api` to `casehub-iot-api`, with two node types (physical-device, device-config), capability-based drift detection, and DeviceCommand dispatch.

**Architecture:** Sealed `IoTNodeSpec` hierarchy (PhysicalDeviceSpec, DeviceConfigSpec) in `casehub-ops-api`. Five SPI implementations in `casehub-ops-iot`: GoalCompiler (YAML → graph with two node types per device), ActualStateAdapter (DeviceRegistry-backed capability comparison), NodeProvisioner (DeviceCommand dispatch with provider routing), FaultPolicy (no-op stub), EventSource (StateChangeEvent CDI bridge). Capability comparison normalizes both sides to BigDecimal + Maps for type-safe comparison across the YAML↔IoT API boundary.

**Tech Stack:** Java 21, Quarkus 3.32, casehub-desiredstate-api, casehub-iot-api, casehub-iot-testing, Jackson YAML, SmallRye Mutiny, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-24-iot-desired-state-design.md` (revision 4, 4 review rounds)

---

## Global Constraints

- Module type: Jandex library — no Quarkus extension, no deployment module
- Single-domain deployment: one domain module per classpath
- `api/` types in `io.casehub.ops.api.iot` — `iot/` implementations in `io.casehub.ops.iot`
- `testing/` is test-scope only
- tenancyId: ProvisionContext.tenancyId() is currently hardcoded to "default" in the runtime — IoT design doesn't depend on it
- Build prerequisite: `casehub-iot` must be installed at 0.2-SNAPSHOT before building

---

## File Structure

### POM changes
| File | Change |
|------|--------|
| `pom.xml` (parent) | Add `casehub-iot-testing` to `<dependencyManagement>` |
| `api/pom.xml` | Add `casehub-iot-api` dependency (for `DeviceClass` in specs) |
| `iot/pom.xml` | Add `casehub-iot-testing` test dep, `casehub-desiredstate` test dep (for `DefaultDesiredStateGraphFactory`/`TransitionPlanner`), `jackson-dataformat-yaml` + `jackson-datatype-jsr310` provided |

### `api/src/main/java/io/casehub/ops/api/iot/`
| File | Responsibility |
|------|---------------|
| `IoTNodeSpec.java` | Sealed interface — common `deviceId()`, `deviceClass()` |
| `PhysicalDeviceSpec.java` | Physical device identity record |
| `DeviceConfigSpec.java` | Desired capabilities record — null rejection, Map.copyOf() |

### `iot/src/main/java/io/casehub/ops/iot/`
| File | Responsibility |
|------|---------------|
| `IoTGoals.java` | Top-level goal declaration record |
| `IoTDeviceGoal.java` | Per-device goal record — boxed Boolean physical |
| `IoTGoalLoader.java` | YAML loading — classpath-first, directory merge |
| `IoTGoalCompiler.java` | GoalCompiler<IoTGoals> — two node types per device, dependency edges |
| `CapabilityNormalizer.java` | Bidirectional type normalization — Temperature→Map, numeric→BigDecimal.stripTrailingZeros(), recursive |
| `CapabilityCommandMapper.java` | Capability key+value → DeviceCommand, with CommandContext inner record |
| `IoTActualStateAdapter.java` | ActualStateAdapter — DeviceRegistry queries, normalized capability comparison |
| `IoTNodeProvisioner.java` | NodeProvisioner — sealed dispatch, provider routing, DeviceCommand dispatch |
| `IoTFaultPolicy.java` | FaultPolicy — no-op stub |
| `IoTEventSource.java` | EventSource — StateChangeEvent + ProviderStatusEvent CDI bridge |

### Test files (`iot/src/test/java/io/casehub/ops/iot/`)
| File | Responsibility |
|------|---------------|
| `IoTNodeSpecTest.java` | API type validation (both specs) |
| `CapabilityNormalizerTest.java` | All conversion rules including recursion and BigDecimal scale |
| `CapabilityCommandMapperTest.java` | All command mappings with CommandContext |
| `IoTGoalCompilerTest.java` | Physical/config-only/dependencies/empty/duplicates |
| `IoTGoalLoaderTest.java` | Single file, directory, merge, duplicate rejection |
| `IoTActualStateAdapterTest.java` | Present/absent/drifted/wrong-class scenarios |
| `IoTNodeProvisionerTest.java` | Config provision, physical provision, deprovision, provider routing |
| `IoTFaultPolicyTest.java` | Returns empty list |
| `IoTEventSourceTest.java` | StateChangeEvent mapping, ProviderStatusEvent device mapping |
| `IoTReconciliationIntegrationTest.java` | Full cycle: compile → readActual → plan → execute |

### Test resources (`iot/src/test/resources/`)
| File | Responsibility |
|------|---------------|
| `iot-topology-simple.yaml` | Single-device fixture |
| `iot-topology-full.yaml` | Multi-device fixture with dependencies |
| `iot-topology-dir/devices-a.yaml` | Directory-merge fixture part 1 |
| `iot-topology-dir/devices-b.yaml` | Directory-merge fixture part 2 |

---

## Task 1: POM Setup + API Types

**Files:**
- Modify: `pom.xml` — add casehub-iot-testing to BOM
- Modify: `api/pom.xml` — add casehub-iot-api dependency
- Modify: `iot/pom.xml` — add casehub-iot-testing test dependency
- Create: `api/src/main/java/io/casehub/ops/api/iot/IoTNodeSpec.java`
- Create: `api/src/main/java/io/casehub/ops/api/iot/PhysicalDeviceSpec.java`
- Create: `api/src/main/java/io/casehub/ops/api/iot/DeviceConfigSpec.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTNodeSpecTest.java`

**Interfaces:**
- Consumes: `NodeSpec` from casehub-desiredstate-api, `DeviceClass` from casehub-iot-api
- Produces: `IoTNodeSpec`, `PhysicalDeviceSpec`, `DeviceConfigSpec` — used by all subsequent tasks

- [ ] **Step 1: Add dependencies to POMs**

Add to `pom.xml` (parent) `<dependencyManagement>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <version>${version.io.casehub}</version>
    <scope>test</scope>
</dependency>
```

Add to `api/pom.xml` `<dependencies>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-api</artifactId>
</dependency>
```

Add to `iot/pom.xml` `<dependencies>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <scope>provided</scope>
</dependency>
```

`casehub-desiredstate` (runtime) in test scope provides `DefaultDesiredStateGraphFactory` and `TransitionPlanner` — following the deployment module pattern. `jackson-dataformat-yaml` and `jackson-datatype-jsr310` are provided scope for the GoalLoader.

- [ ] **Step 2: Write failing tests for API types**

```java
package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceClass;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.IoTNodeSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTNodeSpecTest {

    @Test
    void physicalDeviceSpec_validFields() {
        var spec = new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Living Room");
        assertThat(spec.deviceId()).isEqualTo("dev-1");
        assertThat(spec.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(spec.label()).isEqualTo("Living Room");
        assertThat(spec).isInstanceOf(IoTNodeSpec.class);
    }

    @Test
    void physicalDeviceSpec_nullDeviceIdThrows() {
        assertThatThrownBy(() -> new PhysicalDeviceSpec(null, DeviceClass.THERMOSTAT, "label"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("deviceId");
    }

    @Test
    void deviceConfigSpec_validFields() {
        var caps = Map.<String, Object>of("isOn", true, "brightness", 80);
        var spec = new DeviceConfigSpec("dev-1", DeviceClass.LIGHT, caps);
        assertThat(spec.deviceId()).isEqualTo("dev-1");
        assertThat(spec.desiredCapabilities()).containsEntry("isOn", true);
    }

    @Test
    void deviceConfigSpec_nullValueInCapabilitiesThrows() {
        var caps = new HashMap<String, Object>();
        caps.put("brightness", null);
        assertThatThrownBy(() -> new DeviceConfigSpec("dev-1", DeviceClass.LIGHT, caps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null capability values");
    }

    @Test
    void deviceConfigSpec_capabilitiesAreImmutable() {
        var caps = new HashMap<String, Object>();
        caps.put("isOn", true);
        var spec = new DeviceConfigSpec("dev-1", DeviceClass.SWITCH, caps);
        assertThatThrownBy(() -> spec.desiredCapabilities().put("extra", false))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=IoTNodeSpecTest test --batch-mode`
Expected: compilation failure — IoTNodeSpec, PhysicalDeviceSpec, DeviceConfigSpec do not exist

- [ ] **Step 4: Implement API types**

Create `api/src/main/java/io/casehub/ops/api/iot/IoTNodeSpec.java`:
```java
package io.casehub.ops.api.iot;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.iot.api.DeviceClass;

public sealed interface IoTNodeSpec extends NodeSpec
    permits PhysicalDeviceSpec, DeviceConfigSpec {
    String deviceId();
    DeviceClass deviceClass();
}
```

Create `api/src/main/java/io/casehub/ops/api/iot/PhysicalDeviceSpec.java`:
```java
package io.casehub.ops.api.iot;

import io.casehub.iot.api.DeviceClass;
import java.util.Objects;

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

Create `api/src/main/java/io/casehub/ops/api/iot/DeviceConfigSpec.java`:
```java
package io.casehub.ops.api.iot;

import io.casehub.iot.api.DeviceClass;
import java.util.Map;
import java.util.Objects;

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

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl api,iot -Dtest=IoTNodeSpecTest test --batch-mode`
Expected: all 5 tests PASS

- [ ] **Step 6: Commit**

```
git add api/pom.xml iot/pom.xml pom.xml api/src/main/java/io/casehub/ops/api/iot/ iot/src/test/java/io/casehub/ops/iot/IoTNodeSpecTest.java
git commit -m "feat(#4): API types — IoTNodeSpec sealed hierarchy with PhysicalDeviceSpec, DeviceConfigSpec"
```

---

## Task 2: CapabilityNormalizer

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/CapabilityNormalizer.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/CapabilityNormalizerTest.java`

**Interfaces:**
- Consumes: `Temperature`, `Temperature.TemperatureUnit`, `ThermostatMode` from casehub-iot-api
- Produces: `CapabilityNormalizer.normalize(Map<String, Object>) → Map<String, Object>` — used by Tasks 5 and 6

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.iot;

import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.Temperature.TemperatureUnit;
import io.casehub.iot.api.ThermostatMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityNormalizerTest {

    @Test
    void temperature_convertsToMapWithBigDecimal() {
        var caps = Map.<String, Object>of("targetTemperature",
            new Temperature(new BigDecimal("22"), TemperatureUnit.CELSIUS));
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("targetTemperature"))
            .isEqualTo(Map.of("value", new BigDecimal("22"), "unit", "CELSIUS"));
    }

    @Test
    void enum_convertsToString() {
        var caps = Map.<String, Object>of("mode", ThermostatMode.HEAT);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("mode")).isEqualTo("HEAT");
    }

    @Test
    void integer_convertsToBigDecimal() {
        var caps = Map.<String, Object>of("brightness", 80);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("brightness")).isEqualTo(new BigDecimal("80"));
    }

    @Test
    void double_convertsToBigDecimal() {
        var caps = Map.<String, Object>of("threshold", 22.5);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("threshold")).isInstanceOf(BigDecimal.class);
    }

    @Test
    void boolean_passesThrough() {
        var caps = Map.<String, Object>of("isOn", true);
        var result = CapabilityNormalizer.normalize(caps);
        assertThat(result.get("isOn")).isEqualTo(true);
    }

    @Test
    void stripTrailingZeros_scaleEquality() {
        var caps1 = Map.<String, Object>of("value", new BigDecimal("22.0"));
        var caps2 = Map.<String, Object>of("value", new BigDecimal("22"));
        assertThat(CapabilityNormalizer.normalize(caps1))
            .isEqualTo(CapabilityNormalizer.normalize(caps2));
    }

    @Test
    void recursesIntoNestedMaps() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("value", 22);
        inner.put("unit", "CELSIUS");
        var caps = Map.<String, Object>of("targetTemperature", inner);
        var result = CapabilityNormalizer.normalize(caps);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) result.get("targetTemperature");
        assertThat(nested.get("value")).isEqualTo(new BigDecimal("22"));
        assertThat(nested.get("unit")).isEqualTo("CELSIUS");
    }

    @Test
    void nestedMapWithMixedTypes() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("value", 22.50);
        inner.put("label", "temp");
        inner.put("active", true);
        var caps = Map.<String, Object>of("sensor", inner);
        var result = CapabilityNormalizer.normalize(caps);
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) result.get("sensor");
        assertThat(nested.get("value")).isInstanceOf(BigDecimal.class);
        assertThat(nested.get("label")).isEqualTo("temp");
        assertThat(nested.get("active")).isEqualTo(true);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=CapabilityNormalizerTest test --batch-mode`
Expected: compilation failure — CapabilityNormalizer does not exist

- [ ] **Step 3: Implement CapabilityNormalizer**

```java
package io.casehub.ops.iot;

import io.casehub.iot.api.Temperature;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

final class CapabilityNormalizer {

    private CapabilityNormalizer() {}

    static Map<String, Object> normalize(Map<String, Object> capabilities) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : capabilities.entrySet()) {
            result.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value) {
        if (value instanceof Temperature t) {
            return Map.of(
                "value", t.value().stripTrailingZeros(),
                "unit", t.unit().name());
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Integer i) {
            return BigDecimal.valueOf(i).stripTrailingZeros();
        }
        if (value instanceof Long l) {
            return BigDecimal.valueOf(l).stripTrailingZeros();
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros();
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros();
        }
        if (value instanceof Map<?, ?> m) {
            return normalize((Map<String, Object>) m);
        }
        return value;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl iot -Dtest=CapabilityNormalizerTest test --batch-mode`
Expected: all 8 tests PASS

- [ ] **Step 5: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/CapabilityNormalizer.java iot/src/test/java/io/casehub/ops/iot/CapabilityNormalizerTest.java
git commit -m "feat(#4): CapabilityNormalizer — BigDecimal/enum/Temperature normalization with recursion"
```

---

## Task 3: CapabilityCommandMapper

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/CapabilityCommandMapper.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/CapabilityCommandMapperTest.java`

**Interfaces:**
- Consumes: `DeviceCommand`, `Temperature`, `Temperature.TemperatureUnit` from casehub-iot-api
- Produces: `CapabilityCommandMapper.toCommand(String, String, Object, CommandContext) → DeviceCommand` and `CapabilityCommandMapper.CommandContext` — used by Task 6

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCommandMapperTest {

    private static final CapabilityCommandMapper.CommandContext CTX =
        new CapabilityCommandMapper.CommandContext("test-provisioner", "corr-001");

    @Test
    void isOn_true_turnOn() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isOn", true, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_TURN_ON);
        assertThat(cmd.targetDeviceId()).isEqualTo("dev-1");
        assertThat(cmd.dispatchedBy()).isEqualTo("test-provisioner");
        assertThat(cmd.correlationId()).isEqualTo("corr-001");
    }

    @Test
    void isOn_false_turnOff() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isOn", false, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_TURN_OFF);
    }

    @Test
    void targetTemperature_reconstructsTemperature() {
        var value = Map.<String, Object>of(
            "value", new BigDecimal("22"), "unit", "CELSIUS");
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "targetTemperature", value, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_TEMPERATURE);
        assertThat(cmd.parameters()).containsEntry("unit", "CELSIUS");
    }

    @Test
    void targetTemperature_integerValueConverted() {
        var value = Map.<String, Object>of("value", 22, "unit", "CELSIUS");
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "targetTemperature", value, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_TEMPERATURE);
    }

    @Test
    void isLocked_true_lock() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isLocked", true, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_LOCK);
    }

    @Test
    void isLocked_false_unlock() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isLocked", false, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_UNLOCK);
    }

    @Test
    void position_setPosition() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "position", 50, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_POSITION);
        assertThat(cmd.parameters()).containsEntry("position", 50);
    }

    @Test
    void volume_setVolume() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "volume", 70, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_VOLUME);
        assertThat(cmd.parameters()).containsEntry("volume", 70);
    }

    @Test
    void unknownCapability_genericCommand() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "speed", 3, CTX);
        assertThat(cmd.action()).isEqualTo("set_speed");
        assertThat(cmd.parameters()).containsEntry("speed", 3);
        assertThat(cmd.dispatchedBy()).isEqualTo("test-provisioner");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=CapabilityCommandMapperTest test --batch-mode`
Expected: compilation failure — CapabilityCommandMapper does not exist

- [ ] **Step 3: Implement CapabilityCommandMapper**

```java
package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.Temperature.TemperatureUnit;

import java.math.BigDecimal;
import java.util.Map;

final class CapabilityCommandMapper {

    record CommandContext(String dispatchedBy, String correlationId) {}

    private CapabilityCommandMapper() {}

    @SuppressWarnings("unchecked")
    static DeviceCommand toCommand(String deviceId, String key, Object value,
                                    CommandContext ctx) {
        return switch (key) {
            case "isOn" -> (boolean) value
                ? DeviceCommand.turnOn(deviceId, Map.of(), ctx.dispatchedBy(), ctx.correlationId())
                : DeviceCommand.turnOff(deviceId, ctx.dispatchedBy(), ctx.correlationId());
            case "targetTemperature" -> {
                var map = (Map<String, Object>) value;
                var temp = new Temperature(
                    toBigDecimal(map.get("value")),
                    TemperatureUnit.valueOf((String) map.get("unit")));
                yield DeviceCommand.setTemperature(deviceId, temp,
                    ctx.dispatchedBy(), ctx.correlationId());
            }
            case "isLocked" -> (boolean) value
                ? DeviceCommand.lock(deviceId, ctx.dispatchedBy(), ctx.correlationId())
                : DeviceCommand.unlock(deviceId, ctx.dispatchedBy(), ctx.correlationId());
            case "position" -> DeviceCommand.setPosition(deviceId, ((Number) value).intValue(),
                ctx.dispatchedBy(), ctx.correlationId());
            case "volume" -> DeviceCommand.setVolume(deviceId, ((Number) value).intValue(),
                ctx.dispatchedBy(), ctx.correlationId());
            default -> new DeviceCommand(deviceId, "set_" + key, Map.of(key, value),
                ctx.dispatchedBy(), ctx.correlationId());
        };
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Integer i) return BigDecimal.valueOf(i);
        if (value instanceof Long l) return BigDecimal.valueOf(l);
        if (value instanceof Double d) return BigDecimal.valueOf(d);
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + value);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl iot -Dtest=CapabilityCommandMapperTest test --batch-mode`
Expected: all 9 tests PASS

- [ ] **Step 5: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/CapabilityCommandMapper.java iot/src/test/java/io/casehub/ops/iot/CapabilityCommandMapperTest.java
git commit -m "feat(#4): CapabilityCommandMapper — capability key+value to DeviceCommand with CommandContext"
```

---

## Task 4: Goal Model + GoalCompiler

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTGoals.java`
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTDeviceGoal.java`
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTGoalCompiler.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTGoalCompilerTest.java`

**Interfaces:**
- Consumes: `IoTNodeSpec`, `PhysicalDeviceSpec`, `DeviceConfigSpec` (Task 1), `DesiredStateGraphFactory`, `DesiredNode`, `NodeId`, `NodeType`, `Dependency` from casehub-desiredstate-api
- Produces: `IoTGoals`, `IoTDeviceGoal`, `IoTGoalCompiler implements GoalCompiler<IoTGoals>` — used by Tasks 5, 7, 8

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.DeviceClass;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTGoalCompilerTest {

    private IoTGoalCompiler compiler;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        compiler = new IoTGoalCompiler();
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void physicalTrue_createsTwoNodesAndDependency() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("thermo-1", DeviceClass.THERMOSTAT, "Living Room", true,
                Map.of("targetTemperature", Map.of("value", 22, "unit", "CELSIUS")),
                List.of())));

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().get(NodeId.of("thermo-1")).requiresHuman()).isTrue();
        assertThat(graph.nodes().get(NodeId.of("thermo-1")).spec()).isInstanceOf(PhysicalDeviceSpec.class);
        assertThat(graph.nodes().get(NodeId.of("thermo-1-config")).requiresHuman()).isFalse();
        assertThat(graph.nodes().get(NodeId.of("thermo-1-config")).spec()).isInstanceOf(DeviceConfigSpec.class);
        assertThat(graph.dependencies()).contains(new Dependency(NodeId.of("thermo-1-config"), NodeId.of("thermo-1")));
    }

    @Test
    void physicalFalse_createsSingleConfigNode() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("light-1", DeviceClass.LIGHT, "Porch", false,
                Map.of("isOn", true), List.of())));

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("light-1"));
        assertThat(node).isNotNull();
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.type().value()).isEqualTo("device-config");
    }

    @Test
    void dependsOn_createsDependencyEdge() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("hub", DeviceClass.SWITCH, "Hub", true,
                Map.of("isOn", true), List.of()),
            new IoTDeviceGoal("sensor", DeviceClass.SENSOR, "Hallway", true,
                Map.of(), List.of("hub"))));

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.dependencies()).contains(
            new Dependency(NodeId.of("sensor"), NodeId.of("hub")));
    }

    @Test
    void emptyDeviceList_emptyGraph() {
        var goals = new IoTGoals("tenant-1", List.of());
        DesiredStateGraph graph = compiler.compile(goals, factory);
        assertThat(graph.isEmpty()).isTrue();
    }

    @Test
    void duplicateDeviceId_throws() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.SWITCH, "A", true, Map.of(), List.of()),
            new IoTDeviceGoal("dev-1", DeviceClass.LIGHT, "B", true, Map.of(), List.of())));

        assertThatThrownBy(() -> compiler.compile(goals, factory))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dev-1");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=IoTGoalCompilerTest test --batch-mode`
Expected: compilation failure — IoTGoals, IoTDeviceGoal, IoTGoalCompiler do not exist

- [ ] **Step 3: Implement goal model and compiler**

Create `IoTGoals.java`:
```java
package io.casehub.ops.iot;

import java.util.List;
import java.util.Objects;

public record IoTGoals(String tenancyId, List<IoTDeviceGoal> devices) {
    public IoTGoals {
        Objects.requireNonNull(tenancyId, "tenancyId required");
        devices = List.copyOf(devices);
    }
}
```

Create `IoTDeviceGoal.java`:
```java
package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceClass;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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

Create `IoTGoalCompiler.java`:
```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IoTGoalCompiler implements GoalCompiler<IoTGoals> {

    private static final NodeType PHYSICAL_DEVICE = NodeType.of("physical-device");
    private static final NodeType DEVICE_CONFIG = NodeType.of("device-config");

    @Override
    public DesiredStateGraph compile(IoTGoals goals, DesiredStateGraphFactory factory) {
        Map<String, IoTDeviceGoal> lookup = new HashMap<>();
        for (IoTDeviceGoal goal : goals.devices()) {
            if (lookup.containsKey(goal.deviceId())) {
                throw new IllegalArgumentException("Duplicate deviceId: " + goal.deviceId());
            }
            lookup.put(goal.deviceId(), goal);
        }

        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        for (IoTDeviceGoal goal : goals.devices()) {
            if (goal.physical()) {
                nodes.add(new DesiredNode(
                    NodeId.of(goal.deviceId()), PHYSICAL_DEVICE,
                    new PhysicalDeviceSpec(goal.deviceId(), goal.deviceClass(), goal.label()),
                    true));
                nodes.add(new DesiredNode(
                    NodeId.of(goal.deviceId() + "-config"), DEVICE_CONFIG,
                    new DeviceConfigSpec(goal.deviceId(), goal.deviceClass(), goal.config()),
                    false));
                deps.add(new Dependency(
                    NodeId.of(goal.deviceId() + "-config"), NodeId.of(goal.deviceId())));
            } else {
                nodes.add(new DesiredNode(
                    NodeId.of(goal.deviceId()), DEVICE_CONFIG,
                    new DeviceConfigSpec(goal.deviceId(), goal.deviceClass(), goal.config()),
                    false));
            }

            for (String depId : goal.dependsOn()) {
                deps.add(new Dependency(NodeId.of(goal.deviceId()), NodeId.of(depId)));
            }
        }

        return factory.of(nodes, deps);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl api,iot -Dtest=IoTGoalCompilerTest test --batch-mode`
Expected: all 5 tests PASS

- [ ] **Step 5: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/IoTGoals.java iot/src/main/java/io/casehub/ops/iot/IoTDeviceGoal.java iot/src/main/java/io/casehub/ops/iot/IoTGoalCompiler.java iot/src/test/java/io/casehub/ops/iot/IoTGoalCompilerTest.java
git commit -m "feat(#4): IoTGoalCompiler — two node types, dependency edges, duplicate rejection"
```

---

## Task 5: IoTGoalLoader

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTGoalLoader.java`
- Create: `iot/src/test/resources/iot-topology-simple.yaml`
- Create: `iot/src/test/resources/iot-topology-full.yaml`
- Create: `iot/src/test/resources/iot-topology-dir/devices-a.yaml`
- Create: `iot/src/test/resources/iot-topology-dir/devices-b.yaml`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTGoalLoaderTest.java`

**Interfaces:**
- Consumes: `IoTGoals`, `IoTDeviceGoal` (Task 4)
- Produces: `IoTGoalLoader.load(String) → IoTGoals`, `loadDirectory(String)`, `merge(IoTGoals...)` — used by Task 8

- [ ] **Step 1: Create test YAML fixtures**

Create `iot/src/test/resources/iot-topology-simple.yaml`:
```yaml
tenancyId: test-tenant
devices:
  - deviceId: switch-1
    deviceClass: SWITCH
    label: Hallway Switch
    config:
      isOn: true
```

Create `iot/src/test/resources/iot-topology-full.yaml`:
```yaml
tenancyId: test-tenant
devices:
  - deviceId: hub-1
    deviceClass: SWITCH
    label: Zigbee Hub
    config:
      isOn: true
  - deviceId: thermo-1
    deviceClass: THERMOSTAT
    label: Living Room
    dependsOn: [hub-1]
    config:
      targetTemperature:
        value: 22
        unit: CELSIUS
      mode: HEAT
  - deviceId: light-1
    deviceClass: LIGHT
    label: Porch Light
    physical: false
    config:
      isOn: true
      brightness: 80
```

Create `iot/src/test/resources/iot-topology-dir/devices-a.yaml`:
```yaml
tenancyId: test-tenant
devices:
  - deviceId: lock-1
    deviceClass: LOCK
    label: Front Door
    config:
      isLocked: true
```

Create `iot/src/test/resources/iot-topology-dir/devices-b.yaml`:
```yaml
tenancyId: test-tenant
devices:
  - deviceId: fan-1
    deviceClass: FAN
    label: Bedroom Fan
    config:
      isOn: false
```

- [ ] **Step 2: Write failing tests**

```java
package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTGoalLoaderTest {

    private IoTGoalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new IoTGoalLoader();
    }

    @Test
    void loadSingleFile() {
        IoTGoals goals = loader.load("iot-topology-simple.yaml");
        assertThat(goals.tenancyId()).isEqualTo("test-tenant");
        assertThat(goals.devices()).hasSize(1);
        assertThat(goals.devices().getFirst().deviceId()).isEqualTo("switch-1");
        assertThat(goals.devices().getFirst().deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void loadDirectory() throws URISyntaxException {
        var dirUrl = getClass().getClassLoader().getResource("iot-topology-dir");
        IoTGoals goals = loader.loadDirectory(Path.of(dirUrl.toURI()).toString());
        assertThat(goals.devices()).hasSize(2);
    }

    @Test
    void mergeDuplicateDeviceIdThrows() {
        IoTGoals a = loader.load("iot-topology-simple.yaml");
        IoTGoals b = loader.load("iot-topology-simple.yaml");
        assertThatThrownBy(() -> IoTGoalLoader.merge(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("switch-1");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=IoTGoalLoaderTest test --batch-mode`
Expected: compilation failure — IoTGoalLoader does not exist

- [ ] **Step 4: Implement IoTGoalLoader**

```java
package io.casehub.ops.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class IoTGoalLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public IoTGoals load(String path) {
        try (InputStream is = resolveStream(path)) {
            return yamlMapper.readValue(is, IoTGoals.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load IoT topology from " + path, e);
        }
    }

    public IoTGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        List<IoTGoals> fragments = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".yaml") || name.endsWith(".yml");
                })
                .sorted()
                .forEach(p -> fragments.add(load(p.toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list directory " + directoryPath, e);
        }
        if (fragments.isEmpty()) {
            throw new IllegalArgumentException("No YAML files found in " + directoryPath);
        }
        return merge(fragments.toArray(IoTGoals[]::new));
    }

    public static IoTGoals merge(IoTGoals... fragments) {
        var seen = new HashSet<String>();
        var merged = new ArrayList<IoTDeviceGoal>();
        String tenancyId = fragments[0].tenancyId();
        for (IoTGoals fragment : fragments) {
            for (IoTDeviceGoal device : fragment.devices()) {
                if (!seen.add(device.deviceId())) {
                    throw new IllegalArgumentException(
                        "Duplicate deviceId in merge: " + device.deviceId());
                }
                merged.add(device);
            }
        }
        return new IoTGoals(tenancyId, merged);
    }

    private InputStream resolveStream(String path) throws IOException {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(path);
        if (classpath != null) return classpath;
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) return Files.newInputStream(filePath);
        throw new IOException("Resource not found: " + path);
    }
}
```

Note: `iot/pom.xml` needs `jackson-dataformat-yaml` and `jackson-datatype-jsr310` dependencies. Check if they're already transitively available — if not, add them:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl api,iot -Dtest=IoTGoalLoaderTest test --batch-mode`
Expected: all 3 tests PASS

- [ ] **Step 6: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/IoTGoalLoader.java iot/src/test/java/io/casehub/ops/iot/IoTGoalLoaderTest.java iot/src/test/resources/iot-topology-simple.yaml iot/src/test/resources/iot-topology-full.yaml iot/src/test/resources/iot-topology-dir/ iot/pom.xml
git commit -m "feat(#4): IoTGoalLoader — YAML loading with classpath-first, directory merge, duplicate rejection"
```

---

## Task 6: IoTActualStateAdapter

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTActualStateAdapter.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTActualStateAdapterTest.java`

**Interfaces:**
- Consumes: `ActualStateAdapter`, `ActualState`, `NodeStatus` from casehub-desiredstate-api; `DeviceRegistry` from casehub-iot-api; `CapabilityNormalizer` (Task 2); `IoTNodeSpec`, `PhysicalDeviceSpec`, `DeviceConfigSpec` (Task 1)
- Produces: `IoTActualStateAdapter implements ActualStateAdapter` — used by Task 8

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.Temperature.TemperatureUnit;
import io.casehub.iot.api.ThermostatMode;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTActualStateAdapterTest {

    private static final Instant NOW = Instant.now();
    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test
    void devicePresent_configMatches_present() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singleConfigGraph("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1-config"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void devicePresent_configDrifted_drifted() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(false).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singleConfigGraph("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1-config"))).contains(NodeStatus.DRIFTED);
    }

    @Test
    void deviceAbsent_absent() {
        var registry = stubRegistry();
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singlePhysicalGraph("sw-1", DeviceClass.SWITCH);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1"))).contains(NodeStatus.ABSENT);
    }

    @Test
    void physicalPresent_classMatches_present() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singlePhysicalGraph("sw-1", DeviceClass.SWITCH);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void physicalPresent_wrongClass_drifted() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        var registry = stubRegistry(device);
        var adapter = new IoTActualStateAdapter(registry);

        var graph = singlePhysicalGraph("sw-1", DeviceClass.THERMOSTAT);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("sw-1"))).contains(NodeStatus.DRIFTED);
    }

    private DesiredStateGraph singleConfigGraph(String deviceId, DeviceClass dc,
                                                 Map<String, Object> caps) {
        return FACTORY.of(
            List.of(new DesiredNode(NodeId.of(deviceId + "-config"),
                NodeType.of("device-config"),
                new DeviceConfigSpec(deviceId, dc, caps), false)),
            List.of());
    }

    private DesiredStateGraph singlePhysicalGraph(String deviceId, DeviceClass dc) {
        return FACTORY.of(
            List.of(new DesiredNode(NodeId.of(deviceId),
                NodeType.of("physical-device"),
                new PhysicalDeviceSpec(deviceId, dc, "Label"), true)),
            List.of());
    }

    private DeviceRegistry stubRegistry(DeviceEntity... devices) {
        return new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) {
                for (var d : devices) if (d.deviceId().equals(id)) return Optional.of(d);
                return Optional.empty();
            }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.of(devices); }
            public io.smallrye.mutiny.Uni<Void> refresh() { return io.smallrye.mutiny.Uni.createFrom().voidItem(); }
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=IoTActualStateAdapterTest test --batch-mode`
Expected: compilation failure — IoTActualStateAdapter does not exist

- [ ] **Step 3: Implement IoTActualStateAdapter**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.IoTNodeSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class IoTActualStateAdapter implements ActualStateAdapter {

    private final DeviceRegistry registry;

    @Inject
    public IoTActualStateAdapter(DeviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var entry : desired.nodes().entrySet()) {
            DesiredNode node = entry.getValue();
            if (node.spec() instanceof IoTNodeSpec spec) {
                statuses.put(entry.getKey(), checkNode(spec));
            }
        }
        return new ActualState(statuses);
    }

    private NodeStatus checkNode(IoTNodeSpec spec) {
        var device = registry.findById(spec.deviceId());
        if (device.isEmpty()) return NodeStatus.ABSENT;

        return switch (spec) {
            case PhysicalDeviceSpec s ->
                device.get().deviceClass() == s.deviceClass()
                    ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
            case DeviceConfigSpec s -> {
                var actualNorm = CapabilityNormalizer.normalize(device.get().capabilities());
                var desiredNorm = CapabilityNormalizer.normalize(s.desiredCapabilities());
                boolean matches = desiredNorm.entrySet().stream()
                    .allMatch(e -> e.getValue().equals(actualNorm.get(e.getKey())));
                yield matches ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
            }
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl api,iot -Dtest=IoTActualStateAdapterTest test --batch-mode`
Expected: all 5 tests PASS

- [ ] **Step 5: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/IoTActualStateAdapter.java iot/src/test/java/io/casehub/ops/iot/IoTActualStateAdapterTest.java
git commit -m "feat(#4): IoTActualStateAdapter — DeviceRegistry-backed drift detection with normalized comparison"
```

---

## Task 7: IoTNodeProvisioner

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTNodeProvisioner.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTNodeProvisionerTest.java`

**Interfaces:**
- Consumes: `NodeProvisioner`, `ProvisionResult`, `DeprovisionResult`, `ProvisionContext`, `DeprovisionContext` from casehub-desiredstate-api; `DeviceRegistry`, `DeviceProvider` from casehub-iot-api; `CapabilityNormalizer` (Task 2), `CapabilityCommandMapper` (Task 3)
- Produces: `IoTNodeProvisioner implements NodeProvisioner` — used by Task 8

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTNodeProvisionerTest {

    private static final Instant NOW = Instant.now();
    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test
    void configProvision_deviceFound_dispatchesCommand() {
        var dispatched = new ArrayList<DeviceCommand>();
        var device = switchDevice("sw-1", false);
        var provisioner = provisioner(device, dispatched, CommandResult.SENT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.getFirst().action()).isEqualTo(DeviceCommand.ACTION_TURN_ON);
    }

    @Test
    void configProvision_deviceAbsent_failed() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void configProvision_commandFailed_failed() {
        var device = switchDevice("sw-1", false);
        var provisioner = provisioner(device, new ArrayList<>(), CommandResult.FAILED);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void configProvision_noDrift_noCommandsDispatched() {
        var dispatched = new ArrayList<DeviceCommand>();
        var device = switchDevice("sw-1", true);
        var provisioner = provisioner(device, dispatched, CommandResult.SENT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void physicalProvision_returnsFailed() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var node = new DesiredNode(NodeId.of("dev-1"), NodeType.of("physical-device"),
            new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label"), true);
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void configDeprovision_returnsSuccess() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.deprovision(node, deprovisionContext());

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void noProviderForDevice_returnsFailed() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("unknown-provider")
            .on(false).build();
        var registry = singleDeviceRegistry(device);
        var provisioner = new IoTNodeProvisioner(registry, List.of());

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("no provider");
    }

    private SwitchDevice switchDevice(String id, boolean on) {
        return SwitchDevice.builder()
            .deviceId(id).deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("test-provider")
            .on(on).build();
    }

    private DesiredNode configNode(String id, DeviceClass dc, Map<String, Object> caps) {
        return new DesiredNode(NodeId.of(id + "-config"), NodeType.of("device-config"),
            new DeviceConfigSpec(id, dc, caps), false);
    }

    private ProvisionContext context() {
        return new ProvisionContext("default", FACTORY.empty());
    }

    private DeprovisionContext deprovisionContext() {
        return new DeprovisionContext("default", FACTORY.empty());
    }

    private IoTNodeProvisioner provisioner(DeviceEntity device,
                                            List<DeviceCommand> dispatched,
                                            CommandResult dispatchResult) {
        var registry = device != null ? singleDeviceRegistry(device) : emptyRegistry();
        var provider = new DeviceProvider() {
            public String providerId() { return "test-provider"; }
            public Uni<List<DeviceEntity>> discover() { return Uni.createFrom().item(List.of()); }
            public Uni<CommandResult> dispatch(DeviceCommand cmd) {
                dispatched.add(cmd);
                return Uni.createFrom().item(dispatchResult);
            }
            public ProviderStatus status() { return ProviderStatus.CONNECTED; }
        };
        return new IoTNodeProvisioner(registry, List.of(provider));
    }

    private DeviceRegistry singleDeviceRegistry(DeviceEntity device) {
        return new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) {
                return device.deviceId().equals(id) ? Optional.of(device) : Optional.empty();
            }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.of(device); }
            public Uni<Void> refresh() { return Uni.createFrom().voidItem(); }
        };
    }

    private DeviceRegistry emptyRegistry() {
        return new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) { return Optional.empty(); }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.of(); }
            public Uni<Void> refresh() { return Uni.createFrom().voidItem(); }
        };
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest=IoTNodeProvisionerTest test --batch-mode`
Expected: compilation failure — IoTNodeProvisioner does not exist

- [ ] **Step 3: Implement IoTNodeProvisioner**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class IoTNodeProvisioner implements NodeProvisioner {

    private static final String DISPATCHED_BY = "casehub-ops-iot-provisioner";

    private final DeviceRegistry registry;
    private final Map<String, DeviceProvider> providers;

    @Inject
    public IoTNodeProvisioner(DeviceRegistry registry,
                               @Any Instance<DeviceProvider> providerBeans) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerBeans.forEach(p -> providers.put(p.providerId(), p));
    }

    IoTNodeProvisioner(DeviceRegistry registry, List<DeviceProvider> providerList) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerList.forEach(p -> providers.put(p.providerId(), p));
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        return switch (node.spec()) {
            case PhysicalDeviceSpec s ->
                new ProvisionResult.Failed("physical devices cannot be auto-provisioned");
            case DeviceConfigSpec s -> provisionConfig(s);
            default -> new ProvisionResult.Failed("unknown spec type: " + node.spec().getClass());
        };
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        return switch (node.spec()) {
            case PhysicalDeviceSpec s ->
                new DeprovisionResult.Failed("physical devices cannot be auto-deprovisioned");
            case DeviceConfigSpec s -> new DeprovisionResult.Success();
            default -> new DeprovisionResult.Failed("unknown spec type");
        };
    }

    private ProvisionResult provisionConfig(DeviceConfigSpec spec) {
        var optDevice = registry.findById(spec.deviceId());
        if (optDevice.isEmpty()) {
            return new ProvisionResult.Failed("device not present: " + spec.deviceId());
        }
        var device = optDevice.get();

        var provider = providers.get(device.providerId());
        if (provider == null) {
            return new ProvisionResult.Failed(
                "no provider for '" + device.providerId() + "'");
        }

        var actualNorm = CapabilityNormalizer.normalize(device.capabilities());
        var desiredNorm = CapabilityNormalizer.normalize(spec.desiredCapabilities());
        var ctx = new CapabilityCommandMapper.CommandContext(
            DISPATCHED_BY, UUID.randomUUID().toString());

        for (var entry : desiredNorm.entrySet()) {
            Object actualVal = actualNorm.get(entry.getKey());
            if (!entry.getValue().equals(actualVal)) {
                DeviceCommand cmd = CapabilityCommandMapper.toCommand(
                    spec.deviceId(), entry.getKey(),
                    spec.desiredCapabilities().get(entry.getKey()), ctx);
                CommandResult result = provider.dispatch(cmd).await().indefinitely();
                if (result != CommandResult.SENT) {
                    return new ProvisionResult.Failed(
                        "command " + cmd.action() + " returned " + result);
                }
            }
        }
        return new ProvisionResult.Success();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl api,iot -Dtest=IoTNodeProvisionerTest test --batch-mode`
Expected: all 7 tests PASS

- [ ] **Step 5: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/IoTNodeProvisioner.java iot/src/test/java/io/casehub/ops/iot/IoTNodeProvisionerTest.java
git commit -m "feat(#4): IoTNodeProvisioner — sealed dispatch, provider routing, DeviceCommand dispatch"
```

---

## Task 8: IoTFaultPolicy + IoTEventSource

**Files:**
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTFaultPolicy.java`
- Create: `iot/src/main/java/io/casehub/ops/iot/IoTEventSource.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTFaultPolicyTest.java`
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTEventSourceTest.java`

**Interfaces:**
- Consumes: `FaultPolicy`, `FaultEvent`, `GraphMutation` from casehub-desiredstate-api; `EventSource`, `StateEvent`, `NodeStatus` from casehub-desiredstate-api; `StateChangeEvent`, `ProviderStatusEvent` from casehub-iot-api
- Produces: `IoTFaultPolicy implements FaultPolicy`, `IoTEventSource implements EventSource` — used by Task 9

- [ ] **Step 1: Write failing tests for FaultPolicy**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IoTFaultPolicyTest {

    @Test
    void onFault_returnsEmptyList() {
        var policy = new IoTFaultPolicy();
        var event = new FaultEvent(NodeId.of("dev-1"), FaultType.PROVISION_FAILED, "test");
        var mutations = policy.onFault(event, new DefaultDesiredStateGraphFactory().empty());
        assertThat(mutations).isEmpty();
    }
}
```

- [ ] **Step 2: Write failing tests for EventSource**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.SwitchDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTEventSourceTest {

    private static final Instant NOW = Instant.now();
    private IoTEventSource eventSource;

    @BeforeEach
    void setUp() {
        eventSource = new IoTEventSource();
    }

    @Test
    void stateChange_capabilityDrift_emitsDrifted() {
        var events = subscribe();
        var before = switchDevice("sw-1", true);
        var after = switchDevice("sw-1", false);
        eventSource.onStateChange(
            new StateChangeEvent(before, after, Set.of("isOn"), NOW, "provider-1"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().node().value()).isEqualTo("sw-1-config");
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void stateChange_newDevice_emitsPresent() {
        var events = subscribe();
        var after = switchDevice("sw-1", true);
        eventSource.onStateChange(
            new StateChangeEvent(null, after, Set.of(), NOW, "provider-1"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().node().value()).isEqualTo("sw-1");
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void stateChange_deviceOffline_emitsDrifted() {
        var events = subscribe();
        var before = switchDevice("sw-1", true);
        var after = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(false).lastUpdated(NOW).tenancyId("t").providerId("p")
            .on(true).build();
        eventSource.onStateChange(
            new StateChangeEvent(before, after, Set.of("available"), NOW, "p"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void providerDisconnect_emitsForKnownDevices() {
        var events = subscribe();
        var device = switchDevice("sw-1", true);
        eventSource.onStateChange(
            new StateChangeEvent(null, device, Set.of(), NOW, "provider-1"));
        events.clear();

        eventSource.onProviderStatus(
            new ProviderStatusEvent("provider-1", ProviderStatus.CONNECTED, ProviderStatus.DISCONNECTED));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().newStatus()).isEqualTo(NodeStatus.UNKNOWN);
    }

    @Test
    void providerDisconnect_noKnownDevices_noEmit() {
        var events = subscribe();
        eventSource.onProviderStatus(
            new ProviderStatusEvent("unknown-provider", ProviderStatus.CONNECTED, ProviderStatus.DISCONNECTED));
        assertThat(events).isEmpty();
    }

    private SwitchDevice switchDevice(String id, boolean on) {
        return SwitchDevice.builder()
            .deviceId(id).deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("provider-1")
            .on(on).build();
    }

    private List<StateEvent> subscribe() {
        var collected = new ArrayList<StateEvent>();
        eventSource.stream().subscribe().with(collected::add);
        return collected;
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl iot -Dtest="IoTFaultPolicyTest,IoTEventSourceTest" test --batch-mode`
Expected: compilation failure

- [ ] **Step 4: Implement IoTFaultPolicy**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class IoTFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        return List.of();
    }
}
```

- [ ] **Step 5: Implement IoTEventSource**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class IoTEventSource implements EventSource {

    private final BroadcastProcessor<StateEvent> processor;
    private final Multi<StateEvent> stream;
    private final ConcurrentHashMap<String, Set<String>> providerDevices;

    public IoTEventSource() {
        this.processor = BroadcastProcessor.create();
        this.stream = Multi.createFrom().publisher(processor);
        this.providerDevices = new ConcurrentHashMap<>();
    }

    @Override
    public Multi<StateEvent> stream() {
        return stream;
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        String deviceId = event.after().deviceId();
        String providerId = event.after().providerId();

        providerDevices
            .computeIfAbsent(providerId, k -> ConcurrentHashMap.newKeySet())
            .add(deviceId);

        if (event.before() == null) {
            emit(new StateEvent(NodeId.of(deviceId), NodeStatus.PRESENT,
                "device discovered"));
        } else if (!event.after().available() && event.before().available()) {
            emit(new StateEvent(NodeId.of(deviceId), NodeStatus.DRIFTED,
                "device offline"));
        } else if (!event.changedCapabilities().isEmpty()) {
            emit(new StateEvent(NodeId.of(deviceId + "-config"), NodeStatus.DRIFTED,
                "capabilities changed: " + event.changedCapabilities()));
        }
    }

    void onProviderStatus(@ObservesAsync ProviderStatusEvent event) {
        if (event.currentStatus() == ProviderStatus.DISCONNECTED) {
            Set<String> devices = providerDevices.get(event.providerId());
            if (devices != null) {
                for (String deviceId : devices) {
                    emit(new StateEvent(NodeId.of(deviceId), NodeStatus.UNKNOWN,
                        "provider disconnected: " + event.providerId()));
                }
            }
        }
    }

    private void emit(StateEvent event) {
        processor.onNext(event);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl api,iot -Dtest="IoTFaultPolicyTest,IoTEventSourceTest" test --batch-mode`
Expected: all 6 tests PASS

- [ ] **Step 7: Commit**

```
git add iot/src/main/java/io/casehub/ops/iot/IoTFaultPolicy.java iot/src/main/java/io/casehub/ops/iot/IoTEventSource.java iot/src/test/java/io/casehub/ops/iot/IoTFaultPolicyTest.java iot/src/test/java/io/casehub/ops/iot/IoTEventSourceTest.java
git commit -m "feat(#4): IoTFaultPolicy (no-op) + IoTEventSource (StateChangeEvent/ProviderStatusEvent bridge)"
```

---

## Task 9: Integration Test

**Files:**
- Test: `iot/src/test/java/io/casehub/ops/iot/IoTReconciliationIntegrationTest.java`

**Interfaces:**
- Consumes: All classes from Tasks 1–8

- [ ] **Step 1: Write integration test**

```java
package io.casehub.ops.iot;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.LockDevice;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTReconciliationIntegrationTest {

    private static final Instant NOW = Instant.now();
    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test
    void fullReconciliationCycle() {
        var dispatched = new ArrayList<DeviceCommand>();

        var light = LightDevice.builder()
            .deviceId("light-1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("test")
            .on(true).brightness(50).build();
        var lock = LockDevice.builder()
            .deviceId("lock-1").deviceClass(DeviceClass.LOCK).label("Lock")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("test")
            .locked(true).build();

        var devices = new HashMap<String, DeviceEntity>();
        devices.put("light-1", light);
        devices.put("lock-1", lock);

        DeviceRegistry registry = new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) { return Optional.ofNullable(devices.get(id)); }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.copyOf(devices.values()); }
            public Uni<Void> refresh() { return Uni.createFrom().voidItem(); }
        };

        DeviceProvider provider = new DeviceProvider() {
            public String providerId() { return "test"; }
            public Uni<List<DeviceEntity>> discover() { return Uni.createFrom().item(List.of()); }
            public Uni<CommandResult> dispatch(DeviceCommand cmd) {
                dispatched.add(cmd);
                return Uni.createFrom().item(CommandResult.SENT);
            }
            public ProviderStatus status() { return ProviderStatus.CONNECTED; }
        };

        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("thermo-1", DeviceClass.THERMOSTAT, "Thermostat", true,
                Map.of("targetTemperature", Map.of("value", 22, "unit", "CELSIUS")), List.of()),
            new IoTDeviceGoal("light-1", DeviceClass.LIGHT, "Light", true,
                Map.of("isOn", true, "brightness", 80), List.of()),
            new IoTDeviceGoal("lock-1", DeviceClass.LOCK, "Lock", true,
                Map.of("isLocked", true), List.of())));

        var compiler = new IoTGoalCompiler();
        var adapter = new IoTActualStateAdapter(registry);
        var provisioner = new IoTNodeProvisioner(registry, List.of(provider));
        var planner = new TransitionPlanner();

        var graph = compiler.compile(goals, FACTORY);
        var actual = adapter.readActual(graph);

        assertThat(actual.statusOf(NodeId.of("thermo-1"))).contains(NodeStatus.ABSENT);
        assertThat(actual.statusOf(NodeId.of("light-1"))).contains(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("light-1-config"))).contains(NodeStatus.DRIFTED);
        assertThat(actual.statusOf(NodeId.of("lock-1"))).contains(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("lock-1-config"))).contains(NodeStatus.PRESENT);

        var plan = planner.plan(graph, actual);

        for (var step : plan.additions()) {
            if (step.action() == StepAction.PROVISION) {
                var result = provisioner.provision(step.node(),
                    new ProvisionContext("default", graph));
                if (step.node().id().value().equals("light-1-config")) {
                    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
                }
            }
        }

        assertThat(dispatched).anySatisfy(cmd -> {
            assertThat(cmd.targetDeviceId()).isEqualTo("light-1");
            assertThat(cmd.action()).isEqualTo("set_brightness");
        });
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `mvn -pl api,iot -Dtest=IoTReconciliationIntegrationTest test --batch-mode`
Expected: PASS

- [ ] **Step 3: Run all tests**

Run: `mvn -pl api,iot test --batch-mode`
Expected: all 43+ tests PASS

- [ ] **Step 4: Commit**

```
git add iot/src/test/java/io/casehub/ops/iot/IoTReconciliationIntegrationTest.java
git commit -m "test(#4): integration test — full reconciliation cycle with compile→readActual→plan→provision"
```

---

## Verification

After all tasks complete:

1. **Full build:** `mvn --batch-mode install` — all modules compile and tests pass
2. **Test count:** `mvn -pl iot test --batch-mode` — verify 43+ tests pass
3. **Jandex index:** Verify `iot/target/classes/META-INF/jandex.idx` exists (CDI discovery)
4. **No cross-module leakage:** `mvn -pl iot dependency:tree --batch-mode` — verify no unexpected transitive deps
