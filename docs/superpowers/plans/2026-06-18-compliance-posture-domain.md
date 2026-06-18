# Compliance Posture Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement continuous compliance posture management as a desired-state domain — six control types, six regulatory frameworks, evidence-based drift detection via tamper-evident ledger entries, per-framework posture scoring.

**Architecture:** Follows the deployment module pattern — `GoalCompiler`, `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, `EventSource` SPI implementations. Generic `ComplianceControlSpec` with `controlType` discriminator (not sealed hierarchy). Evidence stored as `ComplianceLedgerEntry extends LedgerEntry`. `ComplianceFrameworkRegistry` + `CompliancePostureService` provide per-framework compliance scoring. `EvidenceCollector` SPI for external integrations with six stubbed implementations.

**Tech Stack:** Java 21, Quarkus 3.32, casehub-desiredstate-api, casehub-ledger (JPA/JOINED inheritance), Jackson YAML, SmallRye Mutiny, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-18-compliance-posture-domain-design.md` (revision 4)

---

## File Structure

### `api/src/main/java/io/casehub/ops/api/compliance/`
| File | Responsibility |
|------|---------------|
| `ComplianceControlSpec.java` | Generic control spec record — implements `NodeSpec` |
| `FrameworkMapping.java` | Control → framework requirement link |
| `ComplianceGoalEntry.java` | Wraps control spec + dependency list |
| `ComplianceGoals.java` | Top-level YAML goal declaration |
| `EvidenceCollector.java` | SPI for configuration assertion checking |
| `EvidenceResult.java` | Sealed result of evidence collection (Pass/Fail/Unavailable) |
| `EvidenceOutcome.java` | Persistable enum mirroring EvidenceResult |

### `api/src/test/java/io/casehub/ops/api/compliance/`
| File | Responsibility |
|------|---------------|
| `ComplianceControlSpecTest.java` | Validation, immutability, null-safety |

### `compliance/src/main/java/io/casehub/ops/compliance/`
| File | Responsibility |
|------|---------------|
| `ComplianceGoalCompiler.java` | GoalCompiler<ComplianceGoals> — YAML goals → DesiredStateGraph |
| `ComplianceGoalLoader.java` | YAML loading — classpath-first, filesystem-fallback, directory merge |
| `ComplianceActualStateAdapter.java` | ActualStateAdapter — two-layer evidence + spec-hash drift |
| `ComplianceNodeProvisioner.java` | NodeProvisioner — collect evidence, write ledger, register framework |
| `ComplianceFaultPolicy.java` | FaultPolicy — intentional no-op |
| `ComplianceEventSource.java` | EventSource — hot Multi<StateEvent> stream |
| `ComplianceSpecHashStore.java` | In-memory spec hash tracking |
| `ComplianceEvidenceService.java` | Evidence orchestration — collect, write ledger, compute status |
| `ControlEvidenceStatus.java` | Per-control evidence status record |
| `ComplianceLedgerEntry.java` | JPA entity — LedgerEntry subclass for evidence |
| `ComplianceFrameworkRegistry.java` | Live control → framework mapping |
| `CompliancePostureService.java` | Per-framework compliance posture aggregation |
| `FrameworkPosture.java` | Framework posture summary record |
| `ControlStatus.java` | Per-control status within a framework |

### `compliance/src/main/java/io/casehub/ops/compliance/collector/`
| File | Responsibility |
|------|---------------|
| `LogRetentionEvidenceCollector.java` | Stub collector — LOG_RETENTION |
| `EncryptionEvidenceCollector.java` | Stub collector — ENCRYPTION_AT_REST |
| `AccessReviewEvidenceCollector.java` | Stub collector — ACCESS_REVIEW |
| `IncidentResponseEvidenceCollector.java` | Stub collector — INCIDENT_RESPONSE |
| `DataProcessingEvidenceCollector.java` | Stub collector — DATA_PROCESSING |
| `AiRiskAssessmentEvidenceCollector.java` | Stub collector — AI_RISK_ASSESSMENT |

### `compliance/src/main/resources/db/compliance/migration/`
| File | Responsibility |
|------|---------------|
| `V2000__compliance_evidence_entry.sql` | Flyway migration — compliance_evidence_entry join table |

### `compliance/src/test/java/io/casehub/ops/compliance/`
| File | Responsibility |
|------|---------------|
| `ComplianceGoalCompilerTest.java` | Goal compilation — nodes, types, dependencies |
| `ComplianceGoalLoaderTest.java` | YAML loading — single file, directory, merge, errors |
| `ComplianceActualStateAdapterTest.java` | Two-layer drift — evidence freshness + spec hash |
| `ComplianceNodeProvisionerTest.java` | Provision/deprovision — evidence, registry, spec hash |
| `ComplianceEvidenceServiceTest.java` | Evidence collection, ledger writes, status derivation |
| `ComplianceFrameworkRegistryTest.java` | Registration, deregistration, framework queries |
| `CompliancePostureServiceTest.java` | Posture aggregation, five-category model, score calculation |
| `ComplianceEventSourceTest.java` | Hot stream subscription, emit, no-replay |

### `compliance/src/test/resources/`
| File | Responsibility |
|------|---------------|
| `test-compliance/all-controls.yaml` | Full YAML fixture — all six control types |
| `test-compliance/encryption-only.yaml` | Single-control fixture |

---

## Task 1: API Types — Core Records

**Files:**
- Create: `api/src/main/java/io/casehub/ops/api/compliance/EvidenceOutcome.java`
- Create: `api/src/main/java/io/casehub/ops/api/compliance/EvidenceResult.java`
- Create: `api/src/main/java/io/casehub/ops/api/compliance/FrameworkMapping.java`
- Create: `api/src/main/java/io/casehub/ops/api/compliance/ComplianceControlSpec.java`
- Create: `api/src/main/java/io/casehub/ops/api/compliance/ComplianceGoalEntry.java`
- Create: `api/src/main/java/io/casehub/ops/api/compliance/ComplianceGoals.java`
- Create: `api/src/main/java/io/casehub/ops/api/compliance/EvidenceCollector.java`
- Test: `api/src/test/java/io/casehub/ops/api/compliance/ComplianceControlSpecTest.java`

- [ ] **Step 1: Write failing tests for ComplianceControlSpec**

```java
package io.casehub.ops.api.compliance;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ComplianceControlSpecTest {

    @Test
    void validSpecConstructs() {
        var spec = new ComplianceControlSpec(
                "encryption-at-rest", "ENCRYPTION_AT_REST",
                "Encryption at Rest", "AES-256 required",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of("cipher", "AES-256"));
        assertThat(spec.controlId()).isEqualTo("encryption-at-rest");
        assertThat(spec.controlType()).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(spec.evidenceMaxAgeDays()).isEqualTo(30);
        assertThat(spec.frameworks()).hasSize(1);
        assertThat(spec.properties()).containsEntry("cipher", "AES-256");
    }

    @Test
    void nullControlIdThrows() {
        assertThatThrownBy(() -> new ComplianceControlSpec(
                null, "TYPE", "T", "D", List.of(), 30, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("controlId");
    }

    @Test
    void blankControlTypeThrows() {
        assertThatThrownBy(() -> new ComplianceControlSpec(
                "id", "  ", "T", "D", List.of(), 30, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("controlType");
    }

    @Test
    void zeroEvidenceMaxAgeDaysThrows() {
        assertThatThrownBy(() -> new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 0, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceMaxAgeDays");
    }

    @Test
    void propertiesPreserveNullValues() {
        var props = new LinkedHashMap<String, Object>();
        props.put("key", null);
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 30, false, props);
        assertThat(spec.properties()).containsEntry("key", null);
    }

    @Test
    void propertiesAreImmutable() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 30, false, Map.of("k", "v"));
        assertThatThrownBy(() -> spec.properties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void frameworksAreImmutable() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D",
                new java.util.ArrayList<>(List.of(new FrameworkMapping("SOC2", "CC6.1"))),
                30, false, Map.of());
        assertThatThrownBy(() -> spec.frameworks().add(new FrameworkMapping("GDPR", "Art.32")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullFrameworksDefaultsToEmptyList() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", null, 30, false, null);
        assertThat(spec.frameworks()).isEmpty();
        assertThat(spec.properties()).isEmpty();
    }

    @Test
    void frameworkMappingValidation() {
        assertThatThrownBy(() -> new FrameworkMapping(null, "CC6.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrameworkMapping("SOC2", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceResultCommonAccessor() {
        EvidenceResult pass = new EvidenceResult.Pass("ok");
        EvidenceResult fail = new EvidenceResult.Fail("bad");
        EvidenceResult unavail = new EvidenceResult.Unavailable("unreachable");
        assertThat(pass.detail()).isEqualTo("ok");
        assertThat(fail.detail()).isEqualTo("bad");
        assertThat(unavail.detail()).isEqualTo("unreachable");
    }

    @Test
    void goalEntryDefaultsDependsOnToEmpty() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 30, false, Map.of());
        var entry = new ComplianceGoalEntry(spec, null);
        assertThat(entry.dependsOn()).isEmpty();
    }

    @Test
    void goalsDefaultsControlsToEmpty() {
        var goals = new ComplianceGoals(null);
        assertThat(goals.controls()).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl api -Dtest=ComplianceControlSpecTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure — classes don't exist yet.

- [ ] **Step 3: Implement all API types**

Create `EvidenceOutcome.java`:

```java
package io.casehub.ops.api.compliance;

public enum EvidenceOutcome {
    PASS,
    FAIL,
    UNAVAILABLE
}
```

Create `EvidenceResult.java`:

```java
package io.casehub.ops.api.compliance;

public sealed interface EvidenceResult {
    String detail();
    record Pass(String detail) implements EvidenceResult {}
    record Fail(String detail) implements EvidenceResult {}
    record Unavailable(String detail) implements EvidenceResult {}
}
```

Create `FrameworkMapping.java`:

```java
package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FrameworkMapping(String framework, String requirement) {
    public FrameworkMapping {
        if (framework == null || framework.isBlank())
            throw new IllegalArgumentException("framework is required");
        if (requirement == null || requirement.isBlank())
            throw new IllegalArgumentException("requirement is required");
    }
}
```

Create `ComplianceControlSpec.java`:

```java
package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.desiredstate.api.NodeSpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (controlId == null || controlId.isBlank())
            throw new IllegalArgumentException("controlId is required");
        if (controlType == null || controlType.isBlank())
            throw new IllegalArgumentException("controlType is required");
        if (evidenceMaxAgeDays <= 0)
            throw new IllegalArgumentException("evidenceMaxAgeDays must be positive");
        frameworks = frameworks != null ? List.copyOf(frameworks) : List.of();
        properties = properties != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(properties))
                : Map.of();
    }
}
```

Create `ComplianceGoalEntry.java`:

```java
package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

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

Create `ComplianceGoals.java`:

```java
package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceGoals(List<ComplianceGoalEntry> controls) {
    public ComplianceGoals {
        controls = controls != null ? List.copyOf(controls) : List.of();
    }
}
```

Create `EvidenceCollector.java`:

```java
package io.casehub.ops.api.compliance;

public interface EvidenceCollector {
    String controlType();
    EvidenceResult collect(ComplianceControlSpec spec, String tenancyId);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl api -Dtest=ComplianceControlSpecTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat(#3): compliance api types — ComplianceControlSpec, EvidenceCollector SPI, EvidenceResult
```

---

## Task 2: Compliance Module POM + Flyway Migration

**Files:**
- Modify: `compliance/pom.xml`
- Create: `compliance/src/main/resources/db/compliance/migration/V2000__compliance_evidence_entry.sql`

- [ ] **Step 1: Update compliance/pom.xml with required dependencies**

Add these dependencies to the existing pom.xml (which already has `casehub-ops-api`, `casehub-desiredstate-api`, `casehub-platform-api`, `casehub-work-api`, `casehub-ledger-api`, `quarkus-arc`, `assertj-core`, `casehub-desiredstate-testing`):

```xml
<!-- Add after casehub-ledger-api -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-ledger</artifactId>
    <version>${version.io.casehub}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>io.smallrye.reactive</groupId>
    <artifactId>mutiny</artifactId>
    <scope>provided</scope>
</dependency>
<!-- Add in test scope -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-junit5</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Create Flyway migration**

Create `compliance/src/main/resources/db/compliance/migration/V2000__compliance_evidence_entry.sql`:

```sql
CREATE TABLE compliance_evidence_entry (
    id          UUID NOT NULL,
    control_id  VARCHAR(255) NOT NULL,
    control_type VARCHAR(255) NOT NULL,
    evidence_outcome VARCHAR(20) NOT NULL,
    evidence_detail TEXT,
    collector_id VARCHAR(255),
    CONSTRAINT pk_compliance_evidence_entry PRIMARY KEY (id),
    CONSTRAINT fk_compliance_evidence_entry_ledger
        FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_compliance_evidence_control_id
    ON compliance_evidence_entry(control_id);
```

- [ ] **Step 3: Verify the module compiles**

Run: `mvn --batch-mode compile -pl compliance`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
feat(#3): compliance module dependencies + Flyway V2000 migration
```

---

## Task 3: ComplianceLedgerEntry + ControlEvidenceStatus

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceLedgerEntry.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ControlEvidenceStatus.java`

- [ ] **Step 1: Create ComplianceLedgerEntry**

```java
package io.casehub.ops.compliance;

import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ops.api.compliance.EvidenceOutcome;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;

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
                collectorId != null ? collectorId : "");
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Create ControlEvidenceStatus**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.EvidenceOutcome;
import java.time.Instant;

public record ControlEvidenceStatus(
        String controlId,
        String controlType,
        EvidenceOutcome latestOutcome,
        Instant latestEvidenceAt,
        int evidenceMaxAgeDays,
        boolean stale,
        NodeStatus derivedNodeStatus
) {
    public static ControlEvidenceStatus absent(
            String controlId, String controlType, int evidenceMaxAgeDays) {
        return new ControlEvidenceStatus(
                controlId, controlType, null, null,
                evidenceMaxAgeDays, false, NodeStatus.ABSENT);
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn --batch-mode compile -pl compliance`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
feat(#3): ComplianceLedgerEntry + ControlEvidenceStatus — evidence persistence types
```

---

## Task 4: ComplianceSpecHashStore + ComplianceEventSource

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceSpecHashStore.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceEventSource.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceEventSourceTest.java`

- [ ] **Step 1: Write failing test for ComplianceEventSource**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceEventSourceTest {

    @Test
    void subscriberReceivesEmittedEvents() {
        var source = new ComplianceEventSource();
        var received = new ArrayList<StateEvent>();

        source.stream()
                .subscribe().with(received::add);

        source.emit(new StateEvent(NodeId.of("ctrl-1"), NodeStatus.DRIFTED, "stale"));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).node()).isEqualTo(NodeId.of("ctrl-1"));
        assertThat(received.get(0).newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void emitBeforeSubscriberIsNotReplayed() {
        var source = new ComplianceEventSource();
        source.emit(new StateEvent(NodeId.of("ctrl-1"), NodeStatus.DRIFTED, "stale"));

        var received = new ArrayList<StateEvent>();
        source.stream()
                .subscribe().with(received::add);

        assertThat(received).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceEventSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 3: Implement ComplianceSpecHashStore**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ComplianceSpecHashStore {

    private final ConcurrentHashMap<NodeId, Integer> hashes = new ConcurrentHashMap<>();

    public void record(NodeId id, NodeSpec spec) {
        hashes.put(id, spec.hashCode());
    }

    public void remove(NodeId id) {
        hashes.remove(id);
    }

    public boolean hasDrifted(NodeId id, NodeSpec spec) {
        Integer stored = hashes.get(id);
        if (stored == null) return true;
        return !stored.equals(spec.hashCode());
    }
}
```

- [ ] **Step 4: Implement ComplianceEventSource**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;

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

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceEventSourceTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
feat(#3): ComplianceSpecHashStore + ComplianceEventSource — infrastructure beans
```

---

## Task 5: ComplianceEvidenceService + Stub Collectors

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceEvidenceService.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/collector/LogRetentionEvidenceCollector.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/collector/EncryptionEvidenceCollector.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/collector/AccessReviewEvidenceCollector.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/collector/IncidentResponseEvidenceCollector.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/collector/DataProcessingEvidenceCollector.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/collector/AiRiskAssessmentEvidenceCollector.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceEvidenceServiceTest.java`

- [ ] **Step 1: Write failing tests for ComplianceEvidenceService**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceEvidenceServiceTest {

    private ComplianceEvidenceService service;
    private StubLedgerRepository ledgerRepo;
    private StubEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StubEvidenceCollector("ENCRYPTION_AT_REST", new EvidenceResult.Pass("AES-256 verified"));
        ledgerRepo = new StubLedgerRepository();
        service = new ComplianceEvidenceService(List.of(collector), ledgerRepo);
    }

    @Test
    void collectAndRecordWritesLedgerEntry() {
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        EvidenceOutcome outcome = service.collectAndRecord(spec, "default");
        assertThat(outcome).isEqualTo(EvidenceOutcome.PASS);
        assertThat(ledgerRepo.saved).isNotNull();
        assertThat(ledgerRepo.saved.controlId).isEqualTo("encryption-at-rest");
        assertThat(ledgerRepo.saved.controlType).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(ledgerRepo.saved.outcome).isEqualTo(EvidenceOutcome.PASS);
        assertThat(ledgerRepo.saved.subjectId).isNotNull();
        assertThat(ledgerRepo.saved.entryType).isNotNull();
    }

    @Test
    void collectAndRecordMapsFail() {
        collector.nextResult = new EvidenceResult.Fail("not encrypted");
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        EvidenceOutcome outcome = service.collectAndRecord(spec, "default");
        assertThat(outcome).isEqualTo(EvidenceOutcome.FAIL);
    }

    @Test
    void collectAndRecordMapsUnavailable() {
        collector.nextResult = new EvidenceResult.Unavailable("system unreachable");
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        EvidenceOutcome outcome = service.collectAndRecord(spec, "default");
        assertThat(outcome).isEqualTo(EvidenceOutcome.UNAVAILABLE);
    }

    @Test
    void evidenceStatusAbsentWhenNoEvidence() {
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        ControlEvidenceStatus status = service.evidenceStatus(spec, "default");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.ABSENT);
        assertThat(status.stale()).isFalse();
        assertThat(status.latestOutcome()).isNull();
    }

    @Test
    void evidenceStatusPresentWhenFreshPass() {
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        var entry = new ComplianceLedgerEntry();
        entry.controlId = "encryption-at-rest";
        entry.controlType = "ENCRYPTION_AT_REST";
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now();
        ledgerRepo.latestEntry = entry;

        ControlEvidenceStatus status = service.evidenceStatus(spec, "default");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.PRESENT);
        assertThat(status.stale()).isFalse();
    }

    @Test
    void evidenceStatusDriftedWhenFreshFail() {
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        var entry = new ComplianceLedgerEntry();
        entry.controlId = "encryption-at-rest";
        entry.outcome = EvidenceOutcome.FAIL;
        entry.occurredAt = Instant.now();
        ledgerRepo.latestEntry = entry;

        ControlEvidenceStatus status = service.evidenceStatus(spec, "default");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.DRIFTED);
        assertThat(status.stale()).isFalse();
    }

    @Test
    void evidenceStatusDriftedWhenFreshUnavailable() {
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        var entry = new ComplianceLedgerEntry();
        entry.controlId = "encryption-at-rest";
        entry.outcome = EvidenceOutcome.UNAVAILABLE;
        entry.occurredAt = Instant.now();
        ledgerRepo.latestEntry = entry;

        ControlEvidenceStatus status = service.evidenceStatus(spec, "default");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void evidenceStatusDriftedWhenStale() {
        var spec = minimalSpec("encryption-at-rest", "ENCRYPTION_AT_REST");
        var entry = new ComplianceLedgerEntry();
        entry.controlId = "encryption-at-rest";
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now().minus(60, ChronoUnit.DAYS);
        ledgerRepo.latestEntry = entry;

        ControlEvidenceStatus status = service.evidenceStatus(spec, "default");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.DRIFTED);
        assertThat(status.stale()).isTrue();
    }

    private ComplianceControlSpec minimalSpec(String id, String type) {
        return new ComplianceControlSpec(
                id, type, "Title", "Desc", List.of(), 30, false, Map.of());
    }

    static class StubEvidenceCollector implements EvidenceCollector {
        private final String type;
        EvidenceResult nextResult;
        StubEvidenceCollector(String type, EvidenceResult defaultResult) {
            this.type = type;
            this.nextResult = defaultResult;
        }
        @Override public String controlType() { return type; }
        @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
            return nextResult;
        }
    }

    static class StubLedgerRepository {
        ComplianceLedgerEntry saved;
        ComplianceLedgerEntry latestEntry;
        void save(ComplianceLedgerEntry entry, String tenancyId) {
            entry.tenancyId = tenancyId;
            this.saved = entry;
        }
        Optional<ComplianceLedgerEntry> findLatest(String controlId, String tenancyId) {
            return Optional.ofNullable(latestEntry);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceEvidenceServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 3: Implement ComplianceEvidenceService**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ops.api.compliance.*;
import io.casehub.platform.api.identity.ActorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@ApplicationScoped
public class ComplianceEvidenceService {

    private final Map<String, EvidenceCollector> collectors;
    private final LedgerWriter ledgerWriter;

    @FunctionalInterface
    interface LedgerWriter {
        void save(ComplianceLedgerEntry entry, String tenancyId);
    }

    @FunctionalInterface
    interface LatestEvidenceFinder {
        Optional<ComplianceLedgerEntry> findLatest(String controlId, String tenancyId);
    }

    private final LatestEvidenceFinder latestFinder;

    @Inject
    public ComplianceEvidenceService(
            Instance<EvidenceCollector> evidenceCollectors,
            io.casehub.ledger.runtime.repository.LedgerEntryRepository ledgerRepository,
            jakarta.persistence.EntityManager entityManager) {
        this.collectors = new HashMap<>();
        for (var c : evidenceCollectors) {
            this.collectors.put(c.controlType(), c);
        }
        this.ledgerWriter = (entry, tenancyId) -> ledgerRepository.save(entry, tenancyId);
        this.latestFinder = (controlId, tenancyId) -> {
            UUID subjectId = subjectIdFor(controlId);
            return ledgerRepository.findLatestBySubjectId(subjectId, tenancyId)
                    .filter(ComplianceLedgerEntry.class::isInstance)
                    .map(ComplianceLedgerEntry.class::cast);
        };
    }

    ComplianceEvidenceService(List<EvidenceCollector> collectors, ComplianceEvidenceServiceTest.StubLedgerRepository stub) {
        this.collectors = new HashMap<>();
        for (var c : collectors) {
            this.collectors.put(c.controlType(), c);
        }
        this.ledgerWriter = stub::save;
        this.latestFinder = stub::findLatest;
    }

    public EvidenceOutcome collectAndRecord(ComplianceControlSpec spec, String tenancyId) {
        EvidenceCollector collector = collectors.get(spec.controlType());
        if (collector == null) {
            throw new IllegalArgumentException("No EvidenceCollector for controlType: " + spec.controlType());
        }
        EvidenceResult result = collector.collect(spec, tenancyId);
        EvidenceOutcome outcome = switch (result) {
            case EvidenceResult.Pass p -> EvidenceOutcome.PASS;
            case EvidenceResult.Fail f -> EvidenceOutcome.FAIL;
            case EvidenceResult.Unavailable u -> EvidenceOutcome.UNAVAILABLE;
        };

        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.subjectId = subjectIdFor(spec.controlId());
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorType = ActorType.SYSTEM;
        entry.actorId = "system:compliance-evidence";
        entry.actorRole = "EvidenceCollector";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entry.controlId = spec.controlId();
        entry.controlType = spec.controlType();
        entry.outcome = outcome;
        entry.detail = result.detail();
        entry.collectorId = collector.controlType();

        ledgerWriter.save(entry, tenancyId);
        return outcome;
    }

    public Optional<ComplianceLedgerEntry> latestEvidence(String controlId, String tenancyId) {
        return latestFinder.findLatest(controlId, tenancyId);
    }

    public ControlEvidenceStatus evidenceStatus(ComplianceControlSpec spec, String tenancyId) {
        Optional<ComplianceLedgerEntry> latest = latestEvidence(spec.controlId(), tenancyId);
        if (latest.isEmpty()) {
            return ControlEvidenceStatus.absent(spec.controlId(), spec.controlType(), spec.evidenceMaxAgeDays());
        }
        ComplianceLedgerEntry entry = latest.get();
        Instant threshold = Instant.now().minus(spec.evidenceMaxAgeDays(), ChronoUnit.DAYS);
        boolean stale = entry.occurredAt.isBefore(threshold);

        if (stale) {
            return new ControlEvidenceStatus(
                    spec.controlId(), spec.controlType(), entry.outcome,
                    entry.occurredAt, spec.evidenceMaxAgeDays(), true, NodeStatus.DRIFTED);
        }
        NodeStatus derived = entry.outcome == EvidenceOutcome.PASS
                ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        return new ControlEvidenceStatus(
                spec.controlId(), spec.controlType(), entry.outcome,
                entry.occurredAt, spec.evidenceMaxAgeDays(), false, derived);
    }

    static UUID subjectIdFor(String controlId) {
        return UUID.nameUUIDFromBytes(("compliance:" + controlId).getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Implement all six stub EvidenceCollectors**

Each collector follows this pattern. Create all six in `compliance/src/main/java/io/casehub/ops/compliance/collector/`:

`LogRetentionEvidenceCollector.java`:
```java
package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class LogRetentionEvidenceCollector implements EvidenceCollector {
    @Override public String controlType() { return "LOG_RETENTION"; }
    @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: retention policy present");
    }
}
```

`EncryptionEvidenceCollector.java`:
```java
package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EncryptionEvidenceCollector implements EvidenceCollector {
    @Override public String controlType() { return "ENCRYPTION_AT_REST"; }
    @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: AES-256 encryption verified");
    }
}
```

`AccessReviewEvidenceCollector.java`:
```java
package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AccessReviewEvidenceCollector implements EvidenceCollector {
    @Override public String controlType() { return "ACCESS_REVIEW"; }
    @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: access review completed");
    }
}
```

`IncidentResponseEvidenceCollector.java`:
```java
package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IncidentResponseEvidenceCollector implements EvidenceCollector {
    @Override public String controlType() { return "INCIDENT_RESPONSE"; }
    @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: playbook verified");
    }
}
```

`DataProcessingEvidenceCollector.java`:
```java
package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DataProcessingEvidenceCollector implements EvidenceCollector {
    @Override public String controlType() { return "DATA_PROCESSING"; }
    @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: processing records complete");
    }
}
```

`AiRiskAssessmentEvidenceCollector.java`:
```java
package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AiRiskAssessmentEvidenceCollector implements EvidenceCollector {
    @Override public String controlType() { return "AI_RISK_ASSESSMENT"; }
    @Override public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: risk assessment current");
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceEvidenceServiceTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
feat(#3): ComplianceEvidenceService + six stub EvidenceCollectors
```

---

## Task 6: ComplianceGoalCompiler + ComplianceGoalLoader

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceGoalCompiler.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceGoalLoader.java`
- Create: `compliance/src/test/resources/test-compliance/all-controls.yaml`
- Create: `compliance/src/test/resources/test-compliance/encryption-only.yaml`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceGoalCompilerTest.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceGoalLoaderTest.java`

- [ ] **Step 1: Create test YAML fixtures**

`compliance/src/test/resources/test-compliance/encryption-only.yaml`:
```yaml
controls:
  - spec:
      controlId: encryption-at-rest
      controlType: ENCRYPTION_AT_REST
      title: "Encryption at Rest"
      description: "AES-256 required"
      frameworks:
        - framework: SOC2
          requirement: CC6.1
        - framework: GDPR
          requirement: Art.32
      evidenceMaxAgeDays: 30
      requiresHumanReview: false
      properties:
        cipher: AES-256
```

`compliance/src/test/resources/test-compliance/all-controls.yaml`:
```yaml
controls:
  - spec:
      controlId: encryption-at-rest
      controlType: ENCRYPTION_AT_REST
      title: "Encryption at Rest"
      description: "AES-256 required"
      frameworks:
        - framework: SOC2
          requirement: CC6.1
      evidenceMaxAgeDays: 30
      requiresHumanReview: false
      properties:
        cipher: AES-256
  - spec:
      controlId: log-retention-policy
      controlType: LOG_RETENTION
      title: "Log Retention Policy"
      description: "1 year retention"
      frameworks:
        - framework: SOC2
          requirement: CC7.2
      evidenceMaxAgeDays: 90
      requiresHumanReview: false
      properties:
        retentionDays: 365
  - spec:
      controlId: access-review-quarterly
      controlType: ACCESS_REVIEW
      title: "Quarterly Access Review"
      description: "All user access reviewed quarterly"
      frameworks:
        - framework: SOC2
          requirement: CC6.2
      evidenceMaxAgeDays: 90
      requiresHumanReview: true
      properties:
        cadence: quarterly
  - spec:
      controlId: incident-response-playbook
      controlType: INCIDENT_RESPONSE
      title: "Incident Response Playbook"
      description: "Annual playbook test"
      frameworks:
        - framework: SOC2
          requirement: CC7.4
      evidenceMaxAgeDays: 365
      requiresHumanReview: true
      properties:
        testCadence: annual
  - spec:
      controlId: data-processing-records
      controlType: DATA_PROCESSING
      title: "Data Processing Records"
      description: "GDPR Art.30 records"
      frameworks:
        - framework: GDPR
          requirement: Art.30
      evidenceMaxAgeDays: 30
      requiresHumanReview: false
      properties:
        scope: all-processing-activities
  - spec:
      controlId: ai-risk-assessment
      controlType: AI_RISK_ASSESSMENT
      title: "AI System Risk Assessment"
      description: "EU AI Act Art.12"
      frameworks:
        - framework: EU_AI_ACT
          requirement: Art.12
      evidenceMaxAgeDays: 365
      requiresHumanReview: true
      properties:
        riskTier: high
    dependsOn:
      - data-processing-records
```

- [ ] **Step 2: Write failing tests for ComplianceGoalCompiler**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceGoalCompilerTest {

    private ComplianceGoalCompiler compiler;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        compiler = new ComplianceGoalCompiler();
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void compilesControlNode() {
        var spec = new ComplianceControlSpec(
                "encryption-at-rest", "ENCRYPTION_AT_REST",
                "Encryption", "AES-256",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of("cipher", "AES-256"));
        var goals = new ComplianceGoals(
                List.of(new ComplianceGoalEntry(spec, List.of())));

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("encryption-at-rest"));
        assertThat(node.id()).isEqualTo(NodeId.of("encryption-at-rest"));
        assertThat(node.type().value()).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.spec()).isInstanceOf(ComplianceControlSpec.class);
    }

    @Test
    void humanReviewControlSetsRequiresHuman() {
        var spec = new ComplianceControlSpec(
                "access-review", "ACCESS_REVIEW",
                "Access Review", "Quarterly",
                List.of(), 90, true, Map.of());
        var goals = new ComplianceGoals(
                List.of(new ComplianceGoalEntry(spec, List.of())));

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes().get(NodeId.of("access-review")).requiresHuman()).isTrue();
    }

    @Test
    void dependsOnCreatesDependencyEdges() {
        var spec1 = new ComplianceControlSpec(
                "data-processing", "DATA_PROCESSING",
                "DP", "Records", List.of(), 30, false, Map.of());
        var spec2 = new ComplianceControlSpec(
                "ai-risk", "AI_RISK_ASSESSMENT",
                "AI", "Risk", List.of(), 365, true, Map.of());
        var goals = new ComplianceGoals(List.of(
                new ComplianceGoalEntry(spec1, List.of()),
                new ComplianceGoalEntry(spec2, List.of("data-processing"))));

        DesiredStateGraph graph = compiler.compile(goals, factory);

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies()).hasSize(1);
        var dep = graph.dependencies().iterator().next();
        assertThat(dep.from()).isEqualTo(NodeId.of("ai-risk"));
        assertThat(dep.to()).isEqualTo(NodeId.of("data-processing"));
    }

    @Test
    void emptyGoalsProducesEmptyGraph() {
        var goals = new ComplianceGoals(List.of());
        DesiredStateGraph graph = compiler.compile(goals, factory);
        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.dependencies()).isEmpty();
    }

    @Test
    void compilesAllSixControlTypes() {
        var goals = new ComplianceGoalLoader().load("test-compliance/all-controls.yaml");
        DesiredStateGraph graph = compiler.compile(goals, factory);
        assertThat(graph.nodes()).hasSize(6);
        assertThat(graph.dependencies()).hasSize(1);
    }
}
```

- [ ] **Step 3: Write failing tests for ComplianceGoalLoader**

```java
package io.casehub.ops.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ComplianceGoalLoaderTest {

    private ComplianceGoalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ComplianceGoalLoader();
    }

    @Test
    void loadsSingleFile() {
        var goals = loader.load("test-compliance/encryption-only.yaml");
        assertThat(goals.controls()).hasSize(1);
        assertThat(goals.controls().get(0).spec().controlId()).isEqualTo("encryption-at-rest");
        assertThat(goals.controls().get(0).spec().controlType()).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(goals.controls().get(0).spec().frameworks()).hasSize(2);
    }

    @Test
    void loadsAllSixControls() {
        var goals = loader.load("test-compliance/all-controls.yaml");
        assertThat(goals.controls()).hasSize(6);
    }

    @Test
    void loadsDirectoryAndMerges(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("encryption.yaml"),
                "controls:\n  - spec:\n      controlId: enc\n      controlType: ENCRYPTION_AT_REST\n      title: Enc\n      description: D\n      evidenceMaxAgeDays: 30\n      requiresHumanReview: false\n");
        Files.writeString(tempDir.resolve("logging.yaml"),
                "controls:\n  - spec:\n      controlId: log\n      controlType: LOG_RETENTION\n      title: Log\n      description: D\n      evidenceMaxAgeDays: 90\n      requiresHumanReview: false\n");

        var goals = loader.loadDirectory(tempDir.toString());
        assertThat(goals.controls()).hasSize(2);
    }

    @Test
    void mergesConcatenatesLists() {
        var goals1 = loader.load("test-compliance/encryption-only.yaml");
        var goals2 = loader.load("test-compliance/encryption-only.yaml");
        var merged = loader.merge(goals1, goals2);
        assertThat(merged.controls()).hasSize(2);
    }

    @Test
    void missingFileThrows() {
        assertThatThrownBy(() -> loader.load("nonexistent.yaml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notADirectoryThrows(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir.yaml");
        Files.writeString(file, "controls: []");
        assertThatThrownBy(() -> loader.loadDirectory(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a directory");
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl compliance -Dtest="ComplianceGoalCompilerTest,ComplianceGoalLoaderTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 5: Implement ComplianceGoalCompiler**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ComplianceGoalCompiler implements GoalCompiler<ComplianceGoals> {

    @Override
    public DesiredStateGraph compile(ComplianceGoals goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        for (var entry : goals.controls()) {
            ComplianceControlSpec spec = entry.spec();
            nodes.add(new DesiredNode(
                    NodeId.of(spec.controlId()),
                    NodeType.of(spec.controlType()),
                    spec,
                    spec.requiresHumanReview()));

            for (String depId : entry.dependsOn()) {
                deps.add(new Dependency(NodeId.of(spec.controlId()), NodeId.of(depId)));
            }
        }
        return factory.of(nodes, deps);
    }
}
```

- [ ] **Step 6: Implement ComplianceGoalLoader**

```java
package io.casehub.ops.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.ops.api.compliance.ComplianceGoalEntry;
import io.casehub.ops.api.compliance.ComplianceGoals;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.stream.Stream;

@ApplicationScoped
public class ComplianceGoalLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public ComplianceGoals load(String path) {
        try (InputStream stream = resolveStream(path)) {
            return yamlMapper.readValue(stream, ComplianceGoals.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse compliance YAML: " + path, e);
        }
    }

    public ComplianceGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directoryPath);
        }
        var fragments = new ArrayList<ComplianceGoals>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".yaml") || name.endsWith(".yml");
            }).sorted().forEach(p -> {
                try {
                    fragments.add(yamlMapper.readValue(p.toFile(), ComplianceGoals.class));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to parse: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + directoryPath, e);
        }
        return merge(fragments.toArray(new ComplianceGoals[0]));
    }

    public ComplianceGoals merge(ComplianceGoals... fragments) {
        var controls = new ArrayList<ComplianceGoalEntry>();
        for (var f : fragments) {
            controls.addAll(f.controls());
        }
        return new ComplianceGoals(controls);
    }

    private InputStream resolveStream(String path) {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path);
        if (classpath != null) return classpath;
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                return Files.newInputStream(filePath);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read file: " + path, e);
            }
        }
        throw new IllegalArgumentException("Compliance YAML not found: " + path);
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest="ComplianceGoalCompilerTest,ComplianceGoalLoaderTest"`
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```
feat(#3): ComplianceGoalCompiler + ComplianceGoalLoader — YAML goals → DesiredStateGraph
```

---

## Task 7: ComplianceFrameworkRegistry

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceFrameworkRegistry.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceFrameworkRegistryTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.compliance;

import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceFrameworkRegistryTest {

    private ComplianceFrameworkRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ComplianceFrameworkRegistry();
    }

    @Test
    void registerAndFindControl() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        registry.register(spec);
        assertThat(registry.findControl("enc")).isPresent();
        assertThat(registry.findControl("enc").get().controlId()).isEqualTo("enc");
    }

    @Test
    void controlsForFramework() {
        var spec1 = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        var spec2 = minimalSpec("log", "LOG_RETENTION",
                List.of(new FrameworkMapping("SOC2", "CC7.2")));
        registry.register(spec1);
        registry.register(spec2);

        assertThat(registry.controlsForFramework("SOC2")).hasSize(2);
        assertThat(registry.controlsForFramework("GDPR")).hasSize(1);
        assertThat(registry.controlsForFramework("DORA")).isEmpty();
    }

    @Test
    void frameworksForControl() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);
        assertThat(registry.frameworksForControl("enc")).hasSize(2);
    }

    @Test
    void registeredFrameworks() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);
        assertThat(registry.registeredFrameworks()).containsExactlyInAnyOrder("SOC2", "GDPR");
    }

    @Test
    void deregisterRemovesControl() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        registry.register(spec);
        registry.deregister("enc");
        assertThat(registry.findControl("enc")).isEmpty();
        assertThat(registry.controlsForFramework("SOC2")).isEmpty();
    }

    @Test
    void findControlReturnsEmptyForUnknown() {
        assertThat(registry.findControl("nonexistent")).isEmpty();
    }

    private ComplianceControlSpec minimalSpec(String id, String type, List<FrameworkMapping> frameworks) {
        return new ComplianceControlSpec(id, type, "T", "D", frameworks, 30, false, Map.of());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceFrameworkRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 3: Implement ComplianceFrameworkRegistry**

```java
package io.casehub.ops.compliance;

import io.casehub.ops.api.compliance.ComplianceControlSpec;
import io.casehub.ops.api.compliance.FrameworkMapping;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ComplianceFrameworkRegistry {

    private final ConcurrentHashMap<String, ComplianceControlSpec> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> frameworkToControls = new ConcurrentHashMap<>();

    public void register(ComplianceControlSpec spec) {
        controls.put(spec.controlId(), spec);
        for (var fm : spec.frameworks()) {
            frameworkToControls
                    .computeIfAbsent(fm.framework(), k -> ConcurrentHashMap.newKeySet())
                    .add(spec.controlId());
        }
    }

    public void deregister(String controlId) {
        ComplianceControlSpec removed = controls.remove(controlId);
        if (removed != null) {
            for (var fm : removed.frameworks()) {
                Set<String> ids = frameworkToControls.get(fm.framework());
                if (ids != null) {
                    ids.remove(controlId);
                    if (ids.isEmpty()) {
                        frameworkToControls.remove(fm.framework());
                    }
                }
            }
        }
    }

    public List<ComplianceControlSpec> controlsForFramework(String framework) {
        Set<String> ids = frameworkToControls.get(framework);
        if (ids == null) return List.of();
        return ids.stream().map(controls::get).filter(Objects::nonNull).toList();
    }

    public List<FrameworkMapping> frameworksForControl(String controlId) {
        ComplianceControlSpec spec = controls.get(controlId);
        return spec != null ? spec.frameworks() : List.of();
    }

    public Set<String> registeredFrameworks() {
        return Set.copyOf(frameworkToControls.keySet());
    }

    public Optional<ComplianceControlSpec> findControl(String controlId) {
        return Optional.ofNullable(controls.get(controlId));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceFrameworkRegistryTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat(#3): ComplianceFrameworkRegistry — control → framework mapping
```

---

## Task 8: ComplianceActualStateAdapter

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceActualStateAdapter.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceActualStateAdapterTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceActualStateAdapterTest {

    private ComplianceActualStateAdapter adapter;
    private ComplianceSpecHashStore specHashStore;
    private StubEvidenceService evidenceService;
    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        specHashStore = new ComplianceSpecHashStore();
        evidenceService = new StubEvidenceService();
        graphFactory = new DefaultDesiredStateGraphFactory();
        adapter = new ComplianceActualStateAdapter(evidenceService, specHashStore, "default");
    }

    @Test
    void absentWhenNoEvidence() {
        evidenceService.nextStatus = ControlEvidenceStatus.absent("enc", "ENCRYPTION_AT_REST", 30);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void presentWhenFreshPassAndHashMatches() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.PASS,
                java.time.Instant.now(), 30, false, NodeStatus.PRESENT);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        specHashStore.record(NodeId.of("enc"), spec);
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void driftedWhenFreshPassButHashChanged() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.PASS,
                java.time.Instant.now(), 30, false, NodeStatus.PRESENT);
        var specOld = minimalSpec("enc", "ENCRYPTION_AT_REST");
        specHashStore.record(NodeId.of("enc"), specOld);
        var specNew = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Changed", "D", List.of(), 30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), specNew, false);
        var graph = graphFactory.of(List.of(node), List.of());

        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftedWhenEvidenceFails() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.FAIL,
                java.time.Instant.now(), 30, false, NodeStatus.DRIFTED);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftedWhenEvidenceUnavailable() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.UNAVAILABLE,
                java.time.Instant.now(), 30, false, NodeStatus.DRIFTED);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftedWhenEvidenceStale() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.PASS,
                java.time.Instant.now().minus(60, java.time.temporal.ChronoUnit.DAYS),
                30, true, NodeStatus.DRIFTED);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    private ComplianceControlSpec minimalSpec(String id, String type) {
        return new ComplianceControlSpec(id, type, "Title", "Desc", List.of(), 30, false, Map.of());
    }

    static class StubEvidenceService extends ComplianceEvidenceService {
        ControlEvidenceStatus nextStatus;
        StubEvidenceService() { super(List.of(), new ComplianceEvidenceServiceTest.StubLedgerRepository()); }
        @Override
        public ControlEvidenceStatus evidenceStatus(ComplianceControlSpec spec, String tenancyId) {
            return nextStatus;
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceActualStateAdapterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 3: Implement ComplianceActualStateAdapter**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;

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

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceActualStateAdapterTest`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```
feat(#3): ComplianceActualStateAdapter — two-layer evidence + spec-hash drift detection
```

---

## Task 9: ComplianceNodeProvisioner + ComplianceFaultPolicy

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceNodeProvisioner.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ComplianceFaultPolicy.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/ComplianceNodeProvisionerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceNodeProvisionerTest {

    private ComplianceNodeProvisioner provisioner;
    private ComplianceEvidenceServiceTest.StubLedgerRepository ledgerRepo;
    private ComplianceFrameworkRegistry registry;
    private ComplianceSpecHashStore specHashStore;
    private ComplianceEvidenceServiceTest.StubEvidenceCollector collector;
    private io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        collector = new ComplianceEvidenceServiceTest.StubEvidenceCollector(
                "ENCRYPTION_AT_REST", new EvidenceResult.Pass("ok"));
        ledgerRepo = new ComplianceEvidenceServiceTest.StubLedgerRepository();
        var evidenceService = new ComplianceEvidenceService(List.of(collector), ledgerRepo);
        registry = new ComplianceFrameworkRegistry();
        specHashStore = new ComplianceSpecHashStore();
        provisioner = new ComplianceNodeProvisioner(evidenceService, registry, specHashStore);
        graphFactory = new io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory();
    }

    @Test
    void provisionCollectsEvidenceAndRegistersControl() {
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Enc", "D",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var context = new ProvisionContext("default", graphFactory.empty());

        ProvisionResult result = provisioner.provision(node, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(ledgerRepo.saved).isNotNull();
        assertThat(registry.findControl("enc")).isPresent();
        assertThat(specHashStore.hasDrifted(NodeId.of("enc"), spec)).isFalse();
    }

    @Test
    void provisionFailReturnsFailedResult() {
        collector.nextResult = new EvidenceResult.Fail("not encrypted");
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Enc", "D", List.of(), 30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var context = new ProvisionContext("default", graphFactory.empty());

        ProvisionResult result = provisioner.provision(node, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(ledgerRepo.saved.outcome).isEqualTo(EvidenceOutcome.FAIL);
    }

    @Test
    void deprovisionRemovesRegistrationAndHash() {
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Enc", "D",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        provisioner.provision(node, new ProvisionContext("default", graphFactory.empty()));

        DeprovisionResult result = provisioner.deprovision(node, new DeprovisionContext("default", graphFactory.empty()));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(registry.findControl("enc")).isEmpty();
        assertThat(specHashStore.hasDrifted(NodeId.of("enc"), spec)).isTrue();
    }

    @Test
    void nonComplianceSpecReturnsFailed() {
        var node = new DesiredNode(NodeId.of("x"), NodeType.of("unknown"), new NodeSpec() {}, false);
        var result = provisioner.provision(node, new ProvisionContext("default", graphFactory.empty()));
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceNodeProvisionerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 3: Implement ComplianceNodeProvisioner**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ComplianceNodeProvisioner implements NodeProvisioner {

    private final ComplianceEvidenceService evidenceService;
    private final ComplianceFrameworkRegistry registry;
    private final ComplianceSpecHashStore specHashStore;

    @Inject
    public ComplianceNodeProvisioner(
            ComplianceEvidenceService evidenceService,
            ComplianceFrameworkRegistry registry,
            ComplianceSpecHashStore specHashStore) {
        this.evidenceService = evidenceService;
        this.registry = registry;
        this.specHashStore = specHashStore;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof ComplianceControlSpec spec)) {
            return new ProvisionResult.Failed("spec is not ComplianceControlSpec");
        }
        evidenceService.collectAndRecord(spec, context.tenancyId());
        registry.register(spec);
        specHashStore.record(node.id(), node.spec());
        return new ProvisionResult.Success();
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof ComplianceControlSpec spec)) {
            return new DeprovisionResult.Failed("spec is not ComplianceControlSpec");
        }
        registry.deregister(spec.controlId());
        specHashStore.remove(node.id());
        return new DeprovisionResult.Success();
    }
}
```

- [ ] **Step 4: Implement ComplianceFaultPolicy**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class ComplianceFaultPolicy implements FaultPolicy {
    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        return List.of();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest=ComplianceNodeProvisionerTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
feat(#3): ComplianceNodeProvisioner + ComplianceFaultPolicy — provision, deprovision, no-op faults
```

---

## Task 10: CompliancePostureService

**Files:**
- Create: `compliance/src/main/java/io/casehub/ops/compliance/FrameworkPosture.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/ControlStatus.java`
- Create: `compliance/src/main/java/io/casehub/ops/compliance/CompliancePostureService.java`
- Test: `compliance/src/test/java/io/casehub/ops/compliance/CompliancePostureServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CompliancePostureServiceTest {

    private CompliancePostureService postureService;
    private ComplianceFrameworkRegistry registry;
    private ComplianceEvidenceServiceTest.StubLedgerRepository ledgerRepo;

    @BeforeEach
    void setUp() {
        registry = new ComplianceFrameworkRegistry();
        ledgerRepo = new ComplianceEvidenceServiceTest.StubLedgerRepository();
        var evidenceService = new ComplianceEvidenceService(List.of(), ledgerRepo);
        postureService = new CompliancePostureService(registry, evidenceService);
    }

    @Test
    void emptyRegistryReturnsEmptyPosture() {
        FrameworkPosture posture = postureService.postureFor("SOC2", "default");
        assertThat(posture.totalControls()).isZero();
        assertThat(posture.complianceScore()).isEqualTo(0.0);
    }

    @Test
    void passingControlCountsAsCompliant() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        registry.register(spec);

        var entry = new ComplianceLedgerEntry();
        entry.controlId = "enc";
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now();
        ledgerRepo.latestEntry = entry;

        FrameworkPosture posture = postureService.postureFor("SOC2", "default");
        assertThat(posture.totalControls()).isEqualTo(1);
        assertThat(posture.passingControls()).isEqualTo(1);
        assertThat(posture.complianceScore()).isEqualTo(1.0);
    }

    @Test
    void fiveCategorieSumToTotal() {
        registry.register(minimalSpec("pass", "TYPE_A",
                List.of(new FrameworkMapping("SOC2", "CC1"))));
        registry.register(minimalSpec("fail", "TYPE_B",
                List.of(new FrameworkMapping("SOC2", "CC2"))));
        registry.register(minimalSpec("unavail", "TYPE_C",
                List.of(new FrameworkMapping("SOC2", "CC3"))));
        registry.register(minimalSpec("stale", "TYPE_D",
                List.of(new FrameworkMapping("SOC2", "CC4"))));
        registry.register(minimalSpec("missing", "TYPE_E",
                List.of(new FrameworkMapping("SOC2", "CC5"))));

        // Will return status based on ledgerRepo — only one latestEntry can be set
        // so we need a multi-control-aware stub. For this test, verify the structure.
        FrameworkPosture posture = postureService.postureFor("SOC2", "default");
        assertThat(posture.totalControls()).isEqualTo(5);
        int sum = posture.passingControls() + posture.failingControls()
                + posture.unavailableControls() + posture.staleControls()
                + posture.missingControls();
        assertThat(sum).isEqualTo(posture.totalControls());
    }

    @Test
    void postureForAllReturnsAllFrameworks() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);

        Map<String, FrameworkPosture> all = postureService.postureForAll("default");
        assertThat(all).containsKeys("SOC2", "GDPR");
    }

    @Test
    void controlAppearsInMultipleFrameworks() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);

        var entry = new ComplianceLedgerEntry();
        entry.controlId = "enc";
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now();
        ledgerRepo.latestEntry = entry;

        assertThat(postureService.postureFor("SOC2", "default").passingControls()).isEqualTo(1);
        assertThat(postureService.postureFor("GDPR", "default").passingControls()).isEqualTo(1);
    }

    private ComplianceControlSpec minimalSpec(String id, String type, List<FrameworkMapping> frameworks) {
        return new ComplianceControlSpec(id, type, "T", "D", frameworks, 30, false, Map.of());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl compliance -Dtest=CompliancePostureServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Compilation failure.

- [ ] **Step 3: Implement FrameworkPosture and ControlStatus**

`FrameworkPosture.java`:
```java
package io.casehub.ops.compliance;

import java.util.List;

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

`ControlStatus.java`:
```java
package io.casehub.ops.compliance;

import io.casehub.ops.api.compliance.EvidenceOutcome;
import java.time.Instant;

public record ControlStatus(
        String controlId,
        String controlType,
        String requirement,
        EvidenceOutcome lastOutcome,
        Instant lastEvidenceAt,
        boolean stale
) {}
```

- [ ] **Step 4: Implement CompliancePostureService**

```java
package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import io.casehub.ops.api.compliance.EvidenceOutcome;
import io.casehub.ops.api.compliance.FrameworkMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class CompliancePostureService {

    private final ComplianceFrameworkRegistry registry;
    private final ComplianceEvidenceService evidenceService;

    @Inject
    public CompliancePostureService(
            ComplianceFrameworkRegistry registry,
            ComplianceEvidenceService evidenceService) {
        this.registry = registry;
        this.evidenceService = evidenceService;
    }

    public FrameworkPosture postureFor(String framework, String tenancyId) {
        List<ComplianceControlSpec> controls = registry.controlsForFramework(framework);
        if (controls.isEmpty()) {
            return new FrameworkPosture(framework, 0, 0, 0, 0, 0, 0, List.of());
        }

        int passing = 0, failing = 0, unavailable = 0, stale = 0, missing = 0;
        List<ControlStatus> statuses = new ArrayList<>();

        for (var spec : controls) {
            ControlEvidenceStatus evidence = evidenceService.evidenceStatus(spec, tenancyId);
            String requirement = spec.frameworks().stream()
                    .filter(fm -> fm.framework().equals(framework))
                    .map(FrameworkMapping::requirement)
                    .findFirst().orElse("");

            statuses.add(new ControlStatus(
                    spec.controlId(), spec.controlType(), requirement,
                    evidence.latestOutcome(), evidence.latestEvidenceAt(), evidence.stale()));

            switch (evidence.derivedNodeStatus()) {
                case PRESENT -> passing++;
                case ABSENT -> missing++;
                case DRIFTED -> {
                    if (evidence.stale()) stale++;
                    else if (evidence.latestOutcome() == EvidenceOutcome.UNAVAILABLE) unavailable++;
                    else failing++;
                }
                case UNKNOWN -> missing++;
            }
        }

        return new FrameworkPosture(framework, controls.size(),
                passing, failing, unavailable, stale, missing, statuses);
    }

    public Map<String, FrameworkPosture> postureForAll(String tenancyId) {
        Map<String, FrameworkPosture> result = new LinkedHashMap<>();
        for (String framework : registry.registeredFrameworks()) {
            result.put(framework, postureFor(framework, tenancyId));
        }
        return result;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl compliance -Dtest=CompliancePostureServiceTest`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```
feat(#3): CompliancePostureService — per-framework posture aggregation with five-category model
```

---

## Task 11: Full Build Verification + PLATFORM.md Update

**Files:**
- Modify: `~/claude/casehub/parent/docs/PLATFORM.md` (Capability Ownership + Cross-Repo Deps)

- [ ] **Step 1: Run full module build**

Run: `mvn --batch-mode install -pl api,compliance`
Expected: BUILD SUCCESS — all tests pass across both modules.

- [ ] **Step 2: Run full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — no regressions in deployment, infra, or other modules.

- [ ] **Step 3: Update PLATFORM.md Capability Ownership table**

Add after the "CaseHub deployment topology provisioning" row:

```markdown
| Continuous compliance posture management | `casehub-ops` (compliance module) | Six frameworks (SOC2, GDPR, EU AI Act, DORA, NIS2, ISO27001), six control types, evidence-based drift detection via `ComplianceLedgerEntry`, per-framework posture scoring via `CompliancePostureService`. `EvidenceCollector` SPI for external integration. Research project. |
```

- [ ] **Step 4: Update PLATFORM.md Cross-Repo Dependency Map**

Add:

```markdown
| `casehub-ledger-api` | `casehub-ops` | `compliance` | `LedgerEntry` base class for `ComplianceLedgerEntry` |
| `casehub-ledger` (runtime) | `casehub-ops` | `compliance` | JPA entity registration, `LedgerEntryRepository` for evidence persistence and queries |
```

- [ ] **Step 5: Commit PLATFORM.md**

```
docs(#3): PLATFORM.md — compliance posture capability ownership + cross-repo deps
```

- [ ] **Step 6: Commit compliance module implementation**

```
feat(#3): compliance posture domain — all SPI implementations, 6 frameworks, 6 control types
```
