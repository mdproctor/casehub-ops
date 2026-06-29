# Real EvidenceCollector Implementations — Design Spec

**Issue:** casehubio/casehub-ops#18
**Date:** 2026-06-29
**Status:** Approved

## Problem

The compliance module defines the `EvidenceCollector` SPI and wires it into the reconciliation cycle, but all 6 implementations are stubs that unconditionally return `Pass("stub: ...")`. To validate the SPI end-to-end and support the compliance demo (#16), collectors that perform real verification are needed.

## Design Decision: Strategy-Based Routing

The existing SPI routes by `controlType` — one collector class per compliance domain. This conflates two orthogonal concerns:

- **Compliance domain** — what you're checking (access review, encryption, log retention)
- **Verification strategy** — how you check (file existence, certificate parsing, hash comparison)

5 of 8 control types use identical verification logic (file exists + is recent). Under the current SPI, each requires its own class with a duplicated `collect()` body.

**Change:** rename `controlType()` → `strategy()` on `EvidenceCollector`. Add a `strategy` field to `ComplianceControlSpec`. Route by strategy, not controlType.

This separates domain (YAML) from verification (Java). New compliance controls that use an existing verification strategy require YAML only — zero Java code.

## SPI Change

```java
// Before
public interface EvidenceCollector {
    String controlType();
    EvidenceResult collect(ComplianceControlSpec spec, String tenancyId);
}

// After
public interface EvidenceCollector {
    String strategy();
    EvidenceResult collect(ComplianceControlSpec spec, String tenancyId);
}
```

Contract: implementations must never throw. Filesystem errors, parse failures, missing files — all return `Unavailable("reason")`.

`EvidenceResult` is unchanged — `Pass(detail)`, `Fail(detail)`, `Unavailable(detail)`.

## ComplianceControlSpec Change

Add `strategy` as a required field with validation:

```java
public record ComplianceControlSpec(
        String controlId,
        String controlType,
        String strategy,
        String title,
        String description,
        List<FrameworkMapping> frameworks,
        int evidenceMaxAgeDays,
        boolean requiresHumanReview,
        Map<String, Object> properties
) implements NodeSpec {
```

- `controlType` — compliance domain identity (reporting, ledger, posture)
- `strategy` — verification method (collector dispatch)

## ComplianceEvidenceService Changes

1. Route by `spec.strategy()` instead of `spec.controlType()`
2. Add try-catch safety net around `collector.collect()` — catches any exception that escapes a collector and records `Unavailable`
3. `collectorId` on ledger entries uses strategy: `"system:" + spec.strategy().toLowerCase().replace('_', '-')`

## 4 Strategies, 8 Control Types

| Strategy | Collector Class | Control Types Served |
|----------|----------------|---------------------|
| `FILE_EXISTENCE` | `FileExistenceEvidenceCollector` | ACCESS_REVIEW, INCIDENT_RESPONSE, DATA_PROCESSING, AI_RISK_ASSESSMENT, ENCRYPTION_AT_REST |
| `LOG_DIRECTORY` | `LogDirectoryEvidenceCollector` | LOG_RETENTION |
| `CERTIFICATE_EXPIRY` | `CertificateExpiryEvidenceCollector` | CERTIFICATE_EXPIRY |
| `CONFIG_HASH` | `ConfigHashEvidenceCollector` | CONFIG_HASH |

### FILE_EXISTENCE

Checks a file exists and was recently modified.

**Properties:**
- `filePath` (required) — absolute path
- `maxAgeDays` (optional) — overrides `spec.evidenceMaxAgeDays()` if set

**Outcomes:**
- Missing `filePath` property → `Unavailable`
- File not found → `Fail`
- File not readable → `Unavailable`
- File older than maxAgeDays → `Fail`
- File exists and recent → `Pass`

### LOG_DIRECTORY

Checks a directory has files with recent activity and historical retention.

**Properties:**
- `logDirectory` (required) — absolute path
- `retentionDays` (required) — minimum age of oldest file

**Outcomes:**
- Directory not found → `Fail`
- Directory empty → `Fail`
- No recent files (within `spec.evidenceMaxAgeDays()`) → `Fail`
- No files old enough for retention → `Fail`
- Recent files AND historical retention satisfied → `Pass`

### CERTIFICATE_EXPIRY

Parses an X.509 certificate and checks validity.

**Properties:**
- `certPath` (required) — path to PEM or DER file
- `warningThresholdDays` (optional, default: 30)

**Outcomes:**
- Expired → `Fail`
- Within warning threshold → `Fail`
- Valid and outside threshold → `Pass`
- File not found or unparseable → `Unavailable`

Uses `java.security.cert.CertificateFactory` and `X509Certificate`. Tries PEM first, then DER.

### CONFIG_HASH

Computes a file hash and compares to a declared baseline.

**Properties:**
- `filePath` (required) — path to config file
- `expectedHash` (required) — expected hash value
- `algorithm` (optional, default: `SHA-256`)

**Outcomes:**
- Hash match → `Pass`
- Hash mismatch → `Fail`
- File not found or invalid algorithm → `Unavailable`

Uses `java.security.MessageDigest`.

## YAML Changes

All test YAML files updated with `strategy` field and real property values. Two new control types added (CERTIFICATE_EXPIRY, CONFIG_HASH).

Example:
```yaml
controls:
  - spec:
      controlId: access-review-quarterly
      controlType: ACCESS_REVIEW
      strategy: FILE_EXISTENCE
      title: "Quarterly Access Review"
      description: "All user access reviewed quarterly"
      frameworks:
        - framework: SOC2
          requirement: CC6.2
      evidenceMaxAgeDays: 90
      requiresHumanReview: true
      properties:
        filePath: /compliance/reviews/access-review-Q2-2026.pdf
        cadence: quarterly
  - spec:
      controlId: cert-mtls
      controlType: CERTIFICATE_EXPIRY
      strategy: CERTIFICATE_EXPIRY
      title: "mTLS Certificate"
      description: "mTLS cert must remain valid"
      frameworks:
        - framework: SOC2
          requirement: CC6.6
      evidenceMaxAgeDays: 30
      requiresHumanReview: false
      properties:
        certPath: /etc/tls/mtls.pem
        warningThresholdDays: 30
```

Compliance-domain properties (`cadence`, `cipher`, `riskTier`, `scope`, `testCadence`) are metadata for reporting — the collector does not read them.

## File Changes

**Deleted (6 stubs):**
- `collector/AccessReviewEvidenceCollector.java`
- `collector/EncryptionEvidenceCollector.java`
- `collector/LogRetentionEvidenceCollector.java`
- `collector/DataProcessingEvidenceCollector.java`
- `collector/AiRiskAssessmentEvidenceCollector.java`
- `collector/IncidentResponseEvidenceCollector.java`

**Created (4 collectors + 4 tests):**
- `collector/FileExistenceEvidenceCollector.java`
- `collector/LogDirectoryEvidenceCollector.java`
- `collector/CertificateExpiryEvidenceCollector.java`
- `collector/ConfigHashEvidenceCollector.java`
- `collector/FileExistenceEvidenceCollectorTest.java`
- `collector/LogDirectoryEvidenceCollectorTest.java`
- `collector/CertificateExpiryEvidenceCollectorTest.java`
- `collector/ConfigHashEvidenceCollectorTest.java`

**Modified:**
- `EvidenceCollector.java` — `controlType()` → `strategy()`
- `ComplianceControlSpec.java` — add `strategy` field + validation
- `ComplianceEvidenceService.java` — route by strategy, add safety net, update collectorId
- `ComplianceEvidenceServiceTest.java` — update stub, add safety-net test
- `ComplianceControlSpecTest.java` — add strategy validation tests
- `all-controls.yaml` — add strategy + real properties to all entries, add 2 new controls
- `encryption-only.yaml` — add strategy field

**Unchanged:**
- `ComplianceLedgerEntry`, `ComplianceGoalCompiler`, `ComplianceActualStateAdapter`, `ComplianceNodeProvisioner`, `CompliancePostureService`, `ComplianceFaultPolicy`, `ComplianceEventSource`, `ComplianceSpecHashStore`, `ComplianceFrameworkRegistry`, `ComplianceGoalLoader`
- `EvidenceResult`, `EvidenceOutcome`, `FrameworkMapping`, `ComplianceGoals`, `ComplianceGoalEntry`
- `ControlEvidenceStatus`, `ControlStatus`, `FrameworkPosture`

## Testing

Each collector gets unit tests using temp files/directories:
- **FileExistence**: temp files with controlled timestamps — fresh, stale, missing, unreadable
- **LogDirectory**: temp directory with files at various ages — validates retention + recency
- **CertificateExpiry**: self-signed X.509 certs generated at test time (valid, expired, near-expiry)
- **ConfigHash**: temp files with known content — match, mismatch, bad algorithm

Service test updated for strategy-based routing and safety-net catch verification.
