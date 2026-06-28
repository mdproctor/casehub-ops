# Channel Drift Checker Enrichment Design

**Issue:** casehubio/casehub-ops#14
**Date:** 2026-06-28
**Origin:** Redirected from casehubio/qhorus#287 (wrong repo — upward coupling)

## Summary

Enrich `ChannelDriftChecker` with full field comparison, connector binding drift
detection, tenancy-aware lookup, CSV set comparison, and reverse binding asymmetry.

## Dependencies — Replace ChannelLookup with Direct Store Injection

Delete the nested `ChannelLookup` functional interface. Replace with constructor
injection of two qhorus store interfaces:

- `CrossTenantChannelStore` — `findByNameAndTenancy(name, tenancyId)`
- `ChannelBindingStore` — `findByChannelId(channelId)`

**Rationale:** Follows the `AgentDriftChecker` → `AgentRegistry` pattern. The
`ChannelLookup` abstraction masked the tenancy bug by hiding `tenancyId` from the
lookup signature. Direct injection makes the tenancy parameter visible and required.

## Tenancy Fix

`check()` currently calls `channelLookup.findByName(channelSpec.name())`, ignoring
the `tenancyId` parameter. Replace with
`channelStore.findByNameAndTenancy(channelSpec.name(), tenancyId)`.

This is a latent bug — without tenancy scoping, a channel in tenant A could
satisfy a spec intended for tenant B.

## Field Comparison

Expand `mutableFieldsMatch()` from 4 fields to 8:

| Field | Spec type | Entity type | Comparison |
|-------|-----------|-------------|------------|
| allowedTypes | `Set<MessageType>` | `String` (CSV) | `typesMatch()` — parse CSV → Set, compare (existing, unified) |
| deniedTypes | `Set<MessageType>` | `String` (CSV) | `typesMatch()` — same helper as allowedTypes (existing, unified) |
| rateLimitPerChannel | `Integer` | `Integer` | `Objects.equals` (existing) |
| rateLimitPerInstance | `Integer` | `Integer` | `Objects.equals` (existing) |
| description | `String` | `String` | `Objects.equals` (new) |
| allowedWriters | `String` (CSV) | `String` (CSV) | Sorted set comparison (new) |
| adminInstances | `String` (CSV) | `String` (CSV) | Sorted set comparison (new) |
| barrierContributors | `String` (CSV) | `String` (CSV) | Sorted set comparison (new) |

`semantic` is excluded — immutable on the entity, set at creation.

## Cleanup — Unify Existing Type Comparison

The existing `allowedTypesMatch()` and `deniedTypesMatch()` are structurally
identical (same null handling, same `MessageType.parseTypes()`, same set
comparison). Collapse into a single `typesMatch(Set<MessageType> desired, String actualCsv)`
helper.

## CSV Set Comparison

`allowedWriters`, `adminInstances`, and `barrierContributors` are comma-separated
strings. `Objects.equals()` gives false drift for `"a,b"` vs `"b,a"`.

Separate private helper (distinct from `typesMatch` above — this one splits raw
strings, not `MessageType` enums): split on comma, trim whitespace, sort, compare
as sets.

Null semantics:
- Both null → match
- One null + one empty/blank string → match
- One null + non-empty → drift

## Connector Binding Drift

After field comparison, check binding drift via
`ChannelBindingStore.findByChannelId(channel.id)`.

Four binding fields compared with `Objects.equals`:

| Spec field | Binding entity field |
|-----------|---------------------|
| `inboundConnectorId` | `inboundConnectorId` |
| `externalKey` | `externalKey` |
| `outboundConnectorId` | `outboundConnectorId` |
| `outboundDestination` | `outboundDestination` |

Cases:

| Spec has binding | Actual has binding | Result |
|-----------------|-------------------|--------|
| Yes (any field non-null) | Yes | Compare all four — any mismatch → DRIFTED |
| Yes | No | DRIFTED (desired binding absent) |
| No (all four null) | Yes | DRIFTED (reverse asymmetry) |
| No | No | No drift |

## Debug Logging

Always check both channel fields and binding — log every mismatched field at
DEBUG before returning DRIFTED. This means the binding lookup executes even when
channel fields already drifted, so the log captures the full drift picture.
Follows the `AgentDriftChecker` pattern:

```
channel dev/work: allowedWriters drifted ["a,b" → "a,c"]
channel dev/work: binding.outboundDestination drifted ["/queue/out" → "/topic/out"]
```

## Testing

Stub implementations of `CrossTenantChannelStore` and `ChannelBindingStore` via
anonymous inner classes (only implementing `findByNameAndTenancy` and
`findByChannelId`; other methods throw `UnsupportedOperationException`).

| Test | Verifies |
|------|----------|
| `channelPresent_allFieldsMatch` | Full field comparison with all 8 fields |
| `channelDrifted_descriptionMismatch` | Description drift detected |
| `channelDrifted_allowedWritersOrderInsensitive` | `"a,b"` vs `"b,a"` → PRESENT |
| `channelDrifted_allowedWritersMismatch` | `"a,b"` vs `"a,c"` → DRIFTED |
| `channelDrifted_adminInstancesMismatch` | adminInstances drift |
| `channelDrifted_barrierContributorsMismatch` | barrierContributors drift |
| `channelPresent_withMatchingBinding` | Binding match → PRESENT |
| `channelDrifted_bindingFieldMismatch` | One binding field differs → DRIFTED |
| `channelDrifted_bindingExpectedButAbsent` | Spec has binding, actual has none → DRIFTED |
| `channelDrifted_bindingPresentButNotInSpec` | Reverse asymmetry → DRIFTED |
| `channelPresent_noBindingEitherSide` | Neither has binding → PRESENT |
| `channelPresent_tenancyUsedInLookup` | Lookup scoped by tenancyId |

## PLATFORM.md Cleanup

Remove the `qhorus#287` reference from `parent/docs/PLATFORM.md` line 413.
Separate commit in the parent repo.

## Files Changed

| File | Change |
|------|--------|
| `deployment/.../drift/ChannelDriftChecker.java` | Replace ChannelLookup, unify type matchers, add fields + binding + tenancy |
| `deployment/.../drift/ChannelDriftCheckerTest.java` | Rewrite stubs, add 12 test cases |
| `deployment/.../DeploymentLifecycleIntegrationTest.java` | Update ChannelDriftChecker construction + add binding stub |
| `casehub-parent/docs/PLATFORM.md` | Remove qhorus#287 reference (separate repo, separate commit) |
