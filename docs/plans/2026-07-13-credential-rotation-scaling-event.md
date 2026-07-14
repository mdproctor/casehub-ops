# Credential Rotation & Scaling-Event Child Case Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #51 — feat: credential rotation and expiration handling in K8sClientRegistry
**Issue group:** #51, #35

**Goal:** Add credential lifecycle management to K8sClientRegistry (proactive refresh, coalesced reactive refresh, watch restart), replace the scaling-event stub with a real child case that evaluates/executes/verifies scaling through desired-state graph mutation, and consolidate convergence tracking into a generic NodeConvergenceTracker.

**Architecture:** K8sClientRegistry gains a `ClientEntry` record storing credential metadata alongside the client, enabling proactive expiration scanning and atomic client replacement. ScalingEventCaseDescriptor follows the DriftRemediationCaseDescriptor pattern (capabilities → workers → bindings → completion predicate) with workers that capture CDI services via lambda closures. NodeConvergenceTracker replaces DriftConvergenceHandler as a generic, parameterized convergence tracker for all case types.

**Tech Stack:** Java 22, Quarkus, fabric8 Kubernetes client, CDI (Jakarta), CloudEvents, AssertJ

## Global Constraints

- Module: `app/` only — no changes to `api/`, `deployment/`, `infra/`, `compliance/`, `iot/`
- Package: `io.casehub.ops.app.k8s` for credential rotation, `io.casehub.ops.app.case_` for scaling descriptor, `io.casehub.ops.app.service` for NodeConvergenceTracker and ApplicationLifecycleService changes
- All new classes `@ApplicationScoped` CDI beans or package-private utility classes
- Tests use AssertJ, JUnit 5, no mocking framework — functional interfaces for test doubles
- project_path for all IntelliJ MCP calls: `/Users/mdproctor/claude/casehub/ops`
- Build verify: `mvn --batch-mode -o test -pl app`
- Pre-release — no backward compatibility constraints

---

### Task 1: K8sClientRegistry — ClientEntry and enriched register()

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java`
- Modify: `app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java`

**Interfaces:**
- Produces: `ClientEntry` record (package-private), `register()` now stores `credentialRef`, `apiUrl`, `trustCerts`, `expiresAt` per entry. Re-registration via `compute()` updates metadata without replacing client. `clientFor(String)` returns `KubernetesClient`.

- [ ] **Step 1: Write failing tests for expires-at parsing and re-registration**

Add three tests to `K8sClientRegistryTest`:

```java
@Test
void registerParsesExpiresAt() {
    Instant expiry = Instant.now().plus(Duration.ofHours(1));
    CredentialResolver resolver = ref -> Map.of("bearer-token", "tok", "expires-at", expiry.toString());
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);
    assertThat(registry.clientFor("c1")).isNotNull();
    // expiresAt stored — verified indirectly via proactive scan in Task 2
}

@Test
void registerWithoutExpiresAtStoresNull() {
    CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);
    assertThat(registry.clientFor("c1")).isNotNull();
    // No expiresAt — proactive scan skips this entry (verified in Task 2)
}

@Test
void reRegisterUpdatesMetadataWithoutReplacingClient() {
    CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds-v1", true);
    KubernetesClient firstClient = registry.clientFor("c1");

    registry.register("c1", "https://localhost:6443", "creds-v2", true);
    KubernetesClient secondClient = registry.clientFor("c1");

    assertThat(secondClient).isSameAs(firstClient); // client not replaced
}
```

Use `ide_insert_member` to add each test to `K8sClientRegistryTest`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest#registerParsesExpiresAt+registerWithoutExpiresAtStoresNull+reRegisterUpdatesMetadataWithoutReplacingClient`
Expected: Compilation failure — `ClientEntry` does not exist yet.

- [ ] **Step 3: Implement ClientEntry and refactor K8sClientRegistry**

In `K8sClientRegistry.java`:

1. Add package-private record inside the class (use `ide_insert_member`):
```java
record ClientEntry(KubernetesClient client, String apiUrl, String credentialRef,
                   boolean trustCerts, Instant expiresAt) {}
```

2. Replace field `clients` declaration (use `ide_edit_member` on the field):
```java
private final ConcurrentHashMap<String, ClientEntry> clients = new ConcurrentHashMap<>();
```

3. Replace `clientFor` method body (use `ide_replace_member`):
```java
ClientEntry entry = clients.get(clusterId);
if (entry == null) {
    throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
}
return entry.client();
```

4. Replace `register(String, String, String, boolean)` method body (use `ide_replace_member`):
```java
Map<String, String> creds = Map.of();
if (credentialRef != null && !credentialRef.isBlank()) {
    creds = credentialResolver.resolve(credentialRef);
    if (creds.isEmpty()) {
        LOG.warning("Credential reference '" + credentialRef
                    + "' resolved to empty map — possible misconfiguration. Falling back to auto-detection.");
    }
}

String expiresAtStr = creds.get("expires-at");
Instant expiresAt = expiresAtStr != null ? Instant.parse(expiresAtStr) : null;

Map<String, String> finalCreds = creds;
clients.compute(clusterId, (id, existing) -> {
    if (existing != null) {
        return new ClientEntry(existing.client(), apiUrl, credentialRef, trustCerts, expiresAt);
    }
    Config config = new ConfigBuilder()
                            .withMasterUrl(apiUrl)
                            .withTrustCerts(trustCerts)
                            .build();
    if (!finalCreds.isEmpty()) {
        applyCredentials(config, finalCreds);
    }
    KubernetesClient client = new KubernetesClientBuilder()
                                      .withConfig(config)
                                      .build();
    return new ClientEntry(client, apiUrl, credentialRef, trustCerts, expiresAt);
});
```

5. Replace `deregister` method body (use `ide_replace_member`):
```java
ClientEntry entry = clients.remove(clusterId);
if (entry != null) {
    entry.client().close();
}
```

6. Replace `shutdown` method body (use `ide_replace_member`):
```java
clients.values().forEach(entry -> entry.client().close());
clients.clear();
```

7. Add `import java.time.Instant;` and `import java.time.Duration;` — handled by reformat=true on edits.

- [ ] **Step 4: Run all K8sClientRegistryTest tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest`
Expected: All tests pass (existing + new).

- [ ] **Step 5: Run ide_diagnostics to verify no compilation errors**

Run `ide_diagnostics` on `K8sClientRegistry.java`.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#51): K8sClientRegistry ClientEntry — credential metadata per cluster, compute()-based re-registration"
```

---

### Task 2: K8sClientRegistry — refreshClient() with coalescing and proactive scan

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/CredentialRefreshedEvent.java`
- Modify: `app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java`

**Interfaces:**
- Consumes: `ClientEntry` record from Task 1
- Produces: `refreshClient(String clusterId)`, `checkExpiring()` scheduled method, `CredentialRefreshedEvent(String clusterId)` CDI event record

- [ ] **Step 1: Write failing tests for refreshClient and proactive scan**

Add tests to `K8sClientRegistryTest`:

```java
@Test
void refreshReplacesClient() {
    AtomicInteger resolveCount = new AtomicInteger();
    CredentialResolver resolver = ref -> {
        resolveCount.incrementAndGet();
        return Map.of("bearer-token", "tok-" + resolveCount.get());
    };
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);
    KubernetesClient before = registry.clientFor("c1");

    registry.refreshClient("c1");
    KubernetesClient after = registry.clientFor("c1");

    assertThat(after).isNotSameAs(before);
    assertThat(resolveCount.get()).isEqualTo(2); // once for register, once for refresh
}

@Test
void refreshPreservesRegistration() {
    CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);

    registry.refreshClient("c1");

    assertThat(registry.clientFor("c1")).isNotNull();
    assertThat(registry.clientFor("c1").getConfiguration().getOauthToken()).isEqualTo("tok");
}

@Test
void refreshUnknownClusterThrows() {
    registry = new K8sClientRegistry(ref -> Map.of());
    assertThatIllegalArgumentException()
            .isThrownBy(() -> registry.refreshClient("unknown"))
            .withMessageContaining("unknown");
}

@Test
void proactiveScanRefreshesApproachingExpiry() {
    Instant nearExpiry = Instant.now().plus(Duration.ofMinutes(3));
    AtomicInteger resolveCount = new AtomicInteger();
    CredentialResolver resolver = ref -> {
        resolveCount.incrementAndGet();
        return Map.of("bearer-token", "tok", "expires-at", nearExpiry.toString());
    };
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);
    int countAfterRegister = resolveCount.get();

    registry.checkExpiring();

    assertThat(resolveCount.get()).isGreaterThan(countAfterRegister);
}

@Test
void proactiveScanSkipsNullExpiry() {
    AtomicInteger resolveCount = new AtomicInteger();
    CredentialResolver resolver = ref -> {
        resolveCount.incrementAndGet();
        return Map.of("bearer-token", "tok");
    };
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);
    int countAfterRegister = resolveCount.get();

    registry.checkExpiring();

    assertThat(resolveCount.get()).isEqualTo(countAfterRegister);
}

@Test
void proactiveScanSkipsDistantExpiry() {
    Instant farExpiry = Instant.now().plus(Duration.ofHours(2));
    AtomicInteger resolveCount = new AtomicInteger();
    CredentialResolver resolver = ref -> {
        resolveCount.incrementAndGet();
        return Map.of("bearer-token", "tok", "expires-at", farExpiry.toString());
    };
    registry = new K8sClientRegistry(resolver);
    registry.register("c1", "https://localhost:6443", "creds", true);
    int countAfterRegister = resolveCount.get();

    registry.checkExpiring();

    assertThat(resolveCount.get()).isEqualTo(countAfterRegister);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest#refreshReplacesClient+refreshPreservesRegistration+refreshUnknownClusterThrows+proactiveScanRefreshesApproachingExpiry+proactiveScanSkipsNullExpiry+proactiveScanSkipsDistantExpiry`
Expected: Compilation failure — `refreshClient`, `checkExpiring` methods do not exist.

- [ ] **Step 3: Create CredentialRefreshedEvent**

Create new file `app/src/main/java/io/casehub/ops/app/k8s/CredentialRefreshedEvent.java`:

```java
package io.casehub.ops.app.k8s;

public record CredentialRefreshedEvent(String clusterId) {}
```

- [ ] **Step 4: Implement refreshClient and checkExpiring**

In `K8sClientRegistry.java`:

1. Add field (use `ide_insert_member` after `credentialResolver` field):
```java
private final ConcurrentHashMap<String, CompletableFuture<Void>> refreshesInFlight = new ConcurrentHashMap<>();
```

2. Add CDI event injection field (use `ide_insert_member` after `credentialResolver` field):
```java
@Inject
jakarta.enterprise.event.Event<CredentialRefreshedEvent> credentialRefreshedEvent;
```

3. Add `refreshClient` method (use `ide_insert_member` after `register` methods):
```java
public void refreshClient(String clusterId) {
    CompletableFuture<Void> future = refreshesInFlight.computeIfAbsent(clusterId, id -> {
        CompletableFuture<Void> f = new CompletableFuture<>();
        try {
            doRefresh(id);
            f.complete(null);
        } catch (Exception e) {
            f.completeExceptionally(e);
        }
        return f;
    });
    future.whenComplete((v, ex) -> refreshesInFlight.remove(clusterId, future));
    future.join();
}

private void doRefresh(String clusterId) {
    ClientEntry existing = clients.get(clusterId);
    if (existing == null) {
        throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
    }

    Map<String, String> creds = credentialResolver.resolve(existing.credentialRef());
    String expiresAtStr = creds.get("expires-at");
    Instant newExpiresAt = expiresAtStr != null ? Instant.parse(expiresAtStr) : null;

    Config config = new ConfigBuilder()
                            .withMasterUrl(existing.apiUrl())
                            .withTrustCerts(existing.trustCerts())
                            .build();
    if (!creds.isEmpty()) {
        applyCredentials(config, creds);
    }
    KubernetesClient newClient = new KubernetesClientBuilder()
                                          .withConfig(config)
                                          .build();

    ClientEntry newEntry = new ClientEntry(newClient, existing.apiUrl(), existing.credentialRef(),
                                            existing.trustCerts(), newExpiresAt);
    ClientEntry old = clients.put(clusterId, newEntry);

    if (credentialRefreshedEvent != null) {
        credentialRefreshedEvent.fire(new CredentialRefreshedEvent(clusterId));
    }

    if (old != null) {
        old.client().close();
    }
}
```

4. Add `checkExpiring` scheduled method (use `ide_insert_member` before `shutdown`):
```java
@io.quarkus.scheduler.Scheduled(every = "60s")
void checkExpiring() {
    for (var entry : clients.entrySet()) {
        try {
            Instant expiresAt = entry.getValue().expiresAt();
            if (expiresAt == null) continue;
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            if (remaining.toMinutes() < 5) {
                LOG.info("Credential for cluster " + entry.getKey() + " expiring in "
                         + remaining.toSeconds() + "s — refreshing");
                refreshClient(entry.getKey());
            }
        } catch (Exception e) {
            LOG.warning("Failed to refresh credential for cluster " + entry.getKey() + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Run all K8sClientRegistryTest tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java app/src/main/java/io/casehub/ops/app/k8s/CredentialRefreshedEvent.java app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#51): refreshClient with coalescing, @Scheduled proactive expiry scan, CredentialRefreshedEvent"
```

---

### Task 3: K8sWatchManager — credential refresh watch restart

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/K8sWatchManager.java`
- Modify: `app/src/test/java/io/casehub/ops/app/k8s/K8sWatchManagerTest.java`

**Interfaces:**
- Consumes: `CredentialRefreshedEvent(String clusterId)` from Task 2
- Produces: `stopWatching(String clusterId)` now returns `Set<String>` of namespaces. `onCredentialRefreshed(@Observes CredentialRefreshedEvent)` handler.

- [ ] **Step 1: Write failing tests**

Add tests to `K8sWatchManagerTest`:

```java
@Test
void credentialRefreshRestartsWatches() {
    clientRegistry.register("c1", "https://localhost:6443");
    watchManager.startWatching("c1", "default");
    assertThat(watchManager.isWatching("c1", "default")).isTrue();

    watchManager.onCredentialRefreshed(new CredentialRefreshedEvent("c1"));

    assertThat(watchManager.isWatching("c1", "default")).isTrue();
    assertThat(watchManager.activeWatchCount()).isEqualTo(1);
}

@Test
void credentialRefreshForUnwatchedClusterIsNoOp() {
    watchManager.onCredentialRefreshed(new CredentialRefreshedEvent("c1"));
    assertThat(watchManager.activeWatchCount()).isEqualTo(0);
}

@Test
void stopWatchingReturnsNamespaces() {
    clientRegistry.register("c1", "https://localhost:6443");
    watchManager.startWatching("c1", "default");
    watchManager.startWatching("c1", "staging");

    Set<String> namespaces = watchManager.stopWatching("c1");

    assertThat(namespaces).containsExactlyInAnyOrder("default", "staging");
    assertThat(watchManager.activeWatchCount()).isEqualTo(0);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sWatchManagerTest#credentialRefreshRestartsWatches+credentialRefreshForUnwatchedClusterIsNoOp+stopWatchingReturnsNamespaces`
Expected: Compilation failure — `onCredentialRefreshed` does not exist, `stopWatching` returns void.

- [ ] **Step 3: Modify stopWatching to return Set<String>**

Use `ide_edit_member` to replace the entire `stopWatching` method:

```java
public Set<String> stopWatching(String clusterId) {
    Set<String> namespaces = new java.util.HashSet<>();
    activeWatches.entrySet().removeIf(entry -> {
        if (entry.getKey().startsWith(clusterId + ":")) {
            namespaces.add(entry.getKey().substring(clusterId.length() + 1));
            entry.getValue().forEach(Watch::close);
            return true;
        }
        return false;
    });
    return namespaces;
}
```

- [ ] **Step 4: Add onCredentialRefreshed handler**

Use `ide_insert_member` after `stopWatching`:

```java
void onCredentialRefreshed(@jakarta.enterprise.event.Observes CredentialRefreshedEvent event) {
    Set<String> namespaces = stopWatching(event.clusterId());
    for (String namespace : namespaces) {
        startWatching(event.clusterId(), namespace);
    }
}
```

- [ ] **Step 5: Run all K8sWatchManagerTest tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sWatchManagerTest`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/k8s/K8sWatchManager.java app/src/test/java/io/casehub/ops/app/k8s/K8sWatchManagerTest.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#51): K8sWatchManager — stopWatching returns namespaces, credential refresh restarts watches"
```

---

### Task 4: NodeConvergenceTracker — generic convergence tracking replacing DriftConvergenceHandler

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/service/NodeConvergenceTracker.java`
- Create: `app/src/test/java/io/casehub/ops/app/service/NodeConvergenceTrackerTest.java`
- Delete: `app/src/main/java/io/casehub/ops/app/service/DriftConvergenceHandler.java` (use `ide_refactor_safe_delete`)
- Delete: `app/src/test/java/io/casehub/ops/app/service/DriftConvergenceHandlerTest.java`

**Interfaces:**
- Consumes: `CaseHubRuntime.signal(UUID, String, Object)`, `NodeRecoveredData`, `DesiredStateEventTypes.NODE_RECOVERED`, `CloudEvent`
- Produces: `NodeConvergenceTracker.register(UUID caseId, Set<String> nodeIds, String signalPath, Map<String, Object> signalValue)`, `isTracking(UUID)`, `onCloudEvent(@ObservesAsync CloudEvent)`

- [ ] **Step 1: Write NodeConvergenceTrackerTest**

Create `app/src/test/java/io/casehub/ops/app/service/NodeConvergenceTrackerTest.java`:

```java
package io.casehub.ops.app.service;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeConvergenceTrackerTest {

    private NodeConvergenceTracker tracker;
    private CopyOnWriteArrayList<SignalRecord> signals;

    @BeforeEach
    void setUp() {
        signals = new CopyOnWriteArrayList<>();
        tracker = new NodeConvergenceTracker(
                (caseId, path, value) -> signals.add(new SignalRecord(caseId, path, value)),
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void signalsConvergedWhenAllNodesRecovered() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", Map.of("scalingStatus", "converged"));

        tracker.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).caseId()).isEqualTo(caseId);
        assertThat(signals.get(0).path()).isEqualTo("scalingStatus");
        assertThat(signals.get(0).value()).isEqualTo(Map.of("scalingStatus", "converged"));
    }

    @Test
    void doesNotSignalUntilAllNodesRecovered() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1", "node-2"),
                "scalingStatus", Map.of("scalingStatus", "converged"));

        tracker.onCloudEvent(recoveredEvent("node-1"));
        assertThat(signals).isEmpty();

        tracker.onCloudEvent(recoveredEvent("node-2"));
        assertThat(signals).hasSize(1);
    }

    @Test
    void ignoresEventsForUnregisteredCases() {
        tracker.onCloudEvent(recoveredEvent("unknown-node"));
        assertThat(signals).isEmpty();
    }

    @Test
    void tracksMultipleCasesWithDifferentSignalPaths() {
        UUID driftCase = UUID.randomUUID();
        UUID scalingCase = UUID.randomUUID();
        tracker.register(driftCase, Set.of("node-d"),
                "remediationStatus", Map.of("remediationStatus", "converged"));
        tracker.register(scalingCase, Set.of("node-s"),
                "scalingStatus", Map.of("scalingStatus", "converged"));

        tracker.onCloudEvent(recoveredEvent("node-d"));
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).path()).isEqualTo("remediationStatus");

        tracker.onCloudEvent(recoveredEvent("node-s"));
        assertThat(signals).hasSize(2);
        assertThat(signals.get(1).path()).isEqualTo("scalingStatus");
    }

    @Test
    void convergenceDeregistersCase() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", Map.of("scalingStatus", "converged"));

        tracker.onCloudEvent(recoveredEvent("node-1"));

        assertThat(tracker.isTracking(caseId)).isFalse();
    }

    @Test
    void duplicateRecoveryEventIgnored() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", Map.of("scalingStatus", "converged"));

        tracker.onCloudEvent(recoveredEvent("node-1"));
        tracker.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signals).hasSize(1);
    }

    @Test
    void nonRecoveredEventIgnored() {
        UUID caseId = UUID.randomUUID();
        tracker.register(caseId, Set.of("node-1"),
                "scalingStatus", Map.of("scalingStatus", "converged"));

        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("/test"))
                .withType(DesiredStateEventTypes.NODE_FAULTED)
                .withData("application/json", "{}".getBytes())
                .build();
        tracker.onCloudEvent(event);

        assertThat(signals).isEmpty();
    }

    private CloudEvent recoveredEvent(String nodeId) {
        try {
            var data = new NodeRecoveredData("tenant:app:cluster", nodeId, "K8S_DEPLOYMENT", 1, null);
            var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("/reconciliation"))
                    .withType(DesiredStateEventTypes.NODE_RECOVERED)
                    .withData("application/json", mapper.writeValueAsBytes(data))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    record SignalRecord(UUID caseId, String path, Object value) {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=NodeConvergenceTrackerTest`
Expected: Compilation failure — `NodeConvergenceTracker` does not exist.

- [ ] **Step 3: Implement NodeConvergenceTracker**

Create `app/src/main/java/io/casehub/ops/app/service/NodeConvergenceTracker.java`:

```java
package io.casehub.ops.app.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class NodeConvergenceTracker {

    private static final Logger LOG = Logger.getLogger(NodeConvergenceTracker.class.getName());

    @FunctionalInterface
    public interface ConvergenceSignaler {
        void signal(UUID caseId, String path, Object value);
    }

    record CaseTracking(Set<String> pendingNodeIds,
                        String signalPath, Map<String, Object> signalValue) {}

    private final ConcurrentHashMap<UUID, CaseTracking> tracked = new ConcurrentHashMap<>();
    private final ConvergenceSignaler signaler;
    private final ObjectMapper objectMapper;

    @Inject
    public NodeConvergenceTracker(io.casehub.api.engine.CaseHubRuntime runtime, ObjectMapper objectMapper) {
        this.signaler = (caseId, path, value) -> runtime.signal(caseId, path, value);
        this.objectMapper = objectMapper;
    }

    NodeConvergenceTracker(ConvergenceSignaler signaler, ObjectMapper objectMapper) {
        this.signaler = signaler;
        this.objectMapper = objectMapper;
    }

    public void register(UUID caseId, Set<String> nodeIds,
                          String signalPath, Map<String, Object> signalValue) {
        var pending = ConcurrentHashMap.<String>newKeySet();
        pending.addAll(nodeIds);
        tracked.put(caseId, new CaseTracking(pending, signalPath, signalValue));
        LOG.fine(() -> "Tracking convergence for case " + caseId + " with " + nodeIds.size()
                       + " nodes, signal path: " + signalPath);
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        if (!DesiredStateEventTypes.NODE_RECOVERED.equals(event.getType())) return;
        if (event.getData() == null) return;

        NodeRecoveredData data;
        try {
            data = objectMapper.readValue(event.getData().toBytes(), NodeRecoveredData.class);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize NodeRecoveredData", e);
            return;
        }

        String recoveredNodeId = data.nodeId();

        for (var entry : tracked.entrySet()) {
            UUID caseId = entry.getKey();
            CaseTracking tracking = entry.getValue();
            if (tracking.pendingNodeIds().remove(recoveredNodeId) && tracking.pendingNodeIds().isEmpty()) {
                tracked.remove(caseId);
                try {
                    signaler.signal(caseId, tracking.signalPath(), tracking.signalValue());
                    LOG.info("Case " + caseId + " converged — all nodes recovered, signaled " + tracking.signalPath());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to signal convergence for case " + caseId, e);
                }
            }
        }
    }

    public boolean isTracking(UUID caseId) {
        return tracked.containsKey(caseId);
    }
}
```

- [ ] **Step 4: Run NodeConvergenceTrackerTest**

Run: `mvn --batch-mode -o test -pl app -Dtest=NodeConvergenceTrackerTest`
Expected: All tests pass.

- [ ] **Step 5: Delete DriftConvergenceHandler and its test**

Use `ide_refactor_safe_delete` on `DriftConvergenceHandler.java` (check for references first — should have no production callers per the review finding). Then delete `DriftConvergenceHandlerTest.java` via bash (test file, not a source file).

```bash
rm /Users/mdproctor/claude/casehub/ops/app/src/test/java/io/casehub/ops/app/service/DriftConvergenceHandlerTest.java
```

- [ ] **Step 6: Run full app test suite**

Run: `mvn --batch-mode -o test -pl app`
Expected: All tests pass (no production code referenced DriftConvergenceHandler).

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add -A app/src/main/java/io/casehub/ops/app/service/NodeConvergenceTracker.java app/src/test/java/io/casehub/ops/app/service/NodeConvergenceTrackerTest.java
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/service/DriftConvergenceHandler.java app/src/test/java/io/casehub/ops/app/service/DriftConvergenceHandlerTest.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#35): NodeConvergenceTracker — generic parameterized convergence tracking, replaces DriftConvergenceHandler"
```

---

### Task 5: DriftRemediationCaseDescriptor — wire convergence tracking via NodeConvergenceTracker

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/case_/DriftRemediationCaseDescriptor.java`
- Modify: `app/src/test/java/io/casehub/ops/app/case_/DriftRemediationCaseDescriptorTest.java`
- Modify: `app/src/main/java/io/casehub/ops/app/case_/CaseDefinitionRegistrar.java`

**Interfaces:**
- Consumes: `NodeConvergenceTracker.register(UUID, Set<String>, String, Map<String, Object>)` from Task 4, `WorkerExecutionContext.current().caseId()` from engine API
- Produces: `DriftRemediationCaseDescriptor.build(NodeConvergenceTracker)` — remediate worker on benign path registers convergence tracking

- [ ] **Step 1: Write failing tests for convergence wiring**

Add tests to `DriftRemediationCaseDescriptorTest`:

```java
@Test
void remediateBenignRegistersConvergence() {
    var tracker = new AtomicReference<UUID>();
    NodeConvergenceTracker mockTracker = new NodeConvergenceTracker(
            (caseId, path, value) -> {},
            new ObjectMapper().registerModule(new JavaTimeModule())) {
        @Override
        public void register(UUID caseId, Set<String> nodeIds, String signalPath, Map<String, Object> signalValue) {
            tracker.set(caseId);
            super.register(caseId, nodeIds, signalPath, signalValue);
        }
    };

    // Set up worker execution context with a case ID
    UUID caseId = UUID.randomUUID();
    WorkerExecutionContext.set(new WorkerContext("test", caseId, List.of(), List.of(), null, Map.of()));

    try {
        CaseDefinition def = DriftRemediationCaseDescriptor.build(mockTracker);
        // Find and invoke the remediate worker with benign classification
        var input = Map.<String, Object>of(
                "driftClassification", Map.of("severity", "benign", "nodeIds", List.of("node-1")));
        WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input, mockTracker);

        assertThat(result.output()).containsEntry("remediationStatus", "auto-remediating");
        assertThat(tracker.get()).isEqualTo(caseId);
    } finally {
        WorkerExecutionContext.clear();
    }
}

@Test
void remediateCriticalDoesNotRegisterConvergence() {
    NodeConvergenceTracker mockTracker = new NodeConvergenceTracker(
            (caseId, path, value) -> {},
            new ObjectMapper().registerModule(new JavaTimeModule()));

    var input = Map.<String, Object>of(
            "driftClassification", Map.of("severity", "critical", "nodeIds", List.of("node-1")));
    WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input, mockTracker);

    assertThat(result.output()).containsEntry("escalationRequired", true);
    assertThat(mockTracker.isTracking(any())).isFalse(); // nothing registered
}
```

Note: The exact test code will need adjustment based on how `build(tracker)` changes the method signatures. The key assertion is: benign → registers convergence, critical → does not.

- [ ] **Step 2: Modify DriftRemediationCaseDescriptor**

Change `build()` to `build(NodeConvergenceTracker tracker)` (use `ide_edit_member`).

Change `remediateDrift` from static to accepting tracker parameter. The worker lambda captures the tracker. On the benign path, after determining severity is benign:
1. Extract `nodeIds` from classification
2. Get `caseId` from `WorkerExecutionContext.current().caseId()`
3. Call `tracker.register(caseId, nodeIds, "remediationStatus", Map.of("remediationStatus", "converged"))`

The escalation path remains unchanged.

- [ ] **Step 3: Update CaseDefinitionRegistrar**

Add `@Inject NodeConvergenceTracker convergenceTracker` field. Change the `DriftRemediationCaseDescriptor.build()` call to `DriftRemediationCaseDescriptor.build(convergenceTracker)`.

- [ ] **Step 4: Run all DriftRemediationCaseDescriptorTest + CaseDefinitionRegistrarTest**

Run: `mvn --batch-mode -o test -pl app -Dtest=DriftRemediationCaseDescriptorTest+CaseDefinitionRegistrarTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/case_/DriftRemediationCaseDescriptor.java app/src/test/java/io/casehub/ops/app/case_/DriftRemediationCaseDescriptorTest.java app/src/main/java/io/casehub/ops/app/case_/CaseDefinitionRegistrar.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#35): wire DriftRemediationCaseDescriptor convergence via NodeConvergenceTracker — benign path registers tracking"
```

---

### Task 6: ApplicationLifecycleService.updateServiceReplicas

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java`
- Modify: `app/src/test/java/io/casehub/ops/app/service/ApplicationLifecycleServiceTest.java` (or create if it doesn't exist)

**Interfaces:**
- Consumes: `ApplicationEntity`, `ServiceDefinitionParser`, `ApplicationGoalCompiler`, `ClusterService`, `ReconciliationLoop.updateDesired()`, `ObjectMapper`
- Produces: `Set<String> updateServiceReplicas(UUID applicationId, String serviceId, int newReplicas, String tenancyId)` — returns affected deployment node IDs

- [ ] **Step 1: Write failing tests**

Create or add to `ApplicationLifecycleServiceTest`:

```java
@Test
void updateServiceReplicasPatchesJson() {
    // Setup: create an ApplicationEntity with servicesJson containing replicas=3
    // Call updateServiceReplicas with newReplicas=6
    // Assert: servicesJson now contains replicas=6
}

@Test
void updateServiceReplicasReturnsAffectedNodeIds() {
    // Assert: returns Set containing "clusterId:serviceId:deployment" per cluster
}

@Test
void updateServiceReplicasUnknownServiceThrows() {
    // Assert: throws IllegalArgumentException for unknown serviceId
}

@Test
void updateServiceReplicasRejectsDraftStatus() {
    // Assert: throws IllegalStateException when app.status == DRAFT
}

@Test
void updateServiceReplicasRejectsDeployingStatus() {
    // Assert: throws IllegalStateException when app.status == DEPLOYING
}
```

The exact test setup depends on the existing test infrastructure (Panache mocking patterns). Use the patterns established in the existing test files.

- [ ] **Step 2: Implement updateServiceReplicas**

Add to `ApplicationLifecycleService` (use `ide_insert_member` after `decommission`):

```java
@Transactional
public Set<String> updateServiceReplicas(UUID applicationId, String serviceId,
                                          int newReplicas, String tenancyId) {
    var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
    if (app == null) throw new IllegalArgumentException("Application not found: " + applicationId);
    if (app.status != ApplicationStatus.RUNNING && app.status != ApplicationStatus.DEGRADED) {
        throw new IllegalStateException("Cannot scale application in status " + app.status
                                        + " — must be RUNNING or DEGRADED");
    }

    List<ServiceDefinition> services = parseServices(app.servicesJson);
    boolean found = false;
    List<ServiceDefinition> updated = new java.util.ArrayList<>();
    for (ServiceDefinition sd : services) {
        if (sd.serviceId().equals(serviceId)) {
            found = true;
            updated.add(new ServiceDefinition(sd.serviceId(), sd.name(), sd.image(), newReplicas,
                    sd.ports(), sd.env(), sd.resources(), sd.dependsOn(), sd.healthCheck(), sd.targetClusters()));
        } else {
            updated.add(sd);
        }
    }
    if (!found) {
        throw new IllegalArgumentException("Service not found: " + serviceId);
    }

    try {
        app.servicesJson = objectMapper.writeValueAsString(updated);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize updated services", e);
    }

    List<ClusterReferenceEntity> clusters = clusterService.list(tenancyId);
    Set<String> affectedNodeIds = new java.util.HashSet<>();

    for (ClusterReferenceEntity cluster : clusters) {
        var graph = goalCompiler.compileForCluster(updated, cluster.id.toString(),
                                                    cluster.namespace, graphFactory);
        String key = tenancyId + ":" + applicationId + ":" + cluster.id;
        reconciliationLoop.updateDesired(key, graph);
        affectedNodeIds.add(cluster.id + ":" + serviceId + ":deployment");
    }

    return affectedNodeIds;
}
```

- [ ] **Step 3: Run tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=ApplicationLifecycleServiceTest`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java app/src/test/java/io/casehub/ops/app/service/ApplicationLifecycleServiceTest.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#35): ApplicationLifecycleService.updateServiceReplicas — patches servicesJson, recompiles graph, returns affected node IDs"
```

---

### Task 7: ScalingEventCaseDescriptor — full implementation

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptor.java`
- Create: `app/src/test/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptorTest.java`
- Modify: `app/src/main/java/io/casehub/ops/app/case_/CaseDefinitionRegistrar.java`

**Interfaces:**
- Consumes: `ApplicationLifecycleService.updateServiceReplicas()` from Task 6, `NodeConvergenceTracker.register()` from Task 4, `WorkerExecutionContext.current().caseId()` from engine API
- Produces: `ScalingEventCaseDescriptor.build(ApplicationLifecycleService, NodeConvergenceTracker)`, CaseDefinition for `ops/scaling-event/1.0`

- [ ] **Step 1: Write ScalingEventCaseDescriptorTest**

Create `app/src/test/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptorTest.java`:

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.ops.app.service.ApplicationLifecycleService;
import io.casehub.ops.app.service.NodeConvergenceTracker;
import io.casehub.worker.api.WorkerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*; // Only if needed — prefer functional test doubles

class ScalingEventCaseDescriptorTest {

    @Test
    void buildReturnsCorrectIdentity() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getNamespace()).isEqualTo("ops");
        assertThat(def.getName()).isEqualTo("scaling-event");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void hasThreeCapabilities() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getCapabilities()).hasSize(3);
        assertThat(def.getCapabilities()).extracting("name")
                .containsExactlyInAnyOrder("evaluate-scaling", "execute-scaling", "verify-convergence");
    }

    @Test
    void hasThreeWorkers() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getWorkers()).hasSize(3);
    }

    @Test
    void hasTwoInternalBindings() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getBindings()).hasSize(2);
    }

    @Test
    void hasCompletionPredicate() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getCompletion()).isNotNull();
    }

    @Test
    void evaluateValidScaleUp() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 6,
                "reason", "cpu_threshold_exceeded");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.output()).containsEntry("action", "scale-up");
        assertThat(result.output().get("scalingDecision")).isNotNull();
    }

    @Test
    void evaluateValidScaleDown() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 6,
                "targetReplicas", 3,
                "reason", "low_utilization");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.output()).containsEntry("action", "scale-down");
    }

    @Test
    void evaluateNoOpSameReplicas() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 3,
                "reason", "periodic_check");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.output()).containsEntry("scalingStatus", "no-change-needed");
    }

    @Test
    void evaluateRejectsZeroTarget() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 0,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Failed.class);
    }

    @Test
    void evaluateRejectsNegativeTarget() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", -1,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Failed.class);
    }

    @Test
    void evaluateRejectsBlankServiceId() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "  ",
                "currentReplicas", 3,
                "targetReplicas", 6,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Failed.class);
    }
}
```

- [ ] **Step 2: Implement ScalingEventCaseDescriptor**

Create `app/src/main/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptor.java` with:
- `build(ApplicationLifecycleService, NodeConvergenceTracker)` returning `CaseDefinition`
- Three capabilities: `evaluate-scaling`, `execute-scaling`, `verify-convergence`
- `evaluateScaling(Map<String, Object>)` — package-private static, validates spec, returns decision or no-change-needed
- Execute worker lambda capturing `ApplicationLifecycleService`, calling `updateServiceReplicas()`, outputting audit trail + `affectedNodeIds`
- Verify worker lambda capturing `NodeConvergenceTracker`, reading `affectedNodeIds` from `scalingExecuted`, registering convergence
- Two bindings: `.scalingDecision` → execute, `.scalingExecuted` → verify
- Completion: `.scalingStatus == "converged"` OR `.scalingStatus == "no-change-needed"`

- [ ] **Step 3: Update CaseDefinitionRegistrar**

Add `@Inject ApplicationLifecycleService applicationLifecycleService` field. Replace `StubChildCaseDescriptor.build("ops", "scaling-event", "1.0")` with `ScalingEventCaseDescriptor.build(applicationLifecycleService, convergenceTracker)`.

- [ ] **Step 4: Run all tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=ScalingEventCaseDescriptorTest+CaseDefinitionRegistrarTest`
Expected: All pass.

- [ ] **Step 5: Run full app test suite**

Run: `mvn --batch-mode -o test -pl app`
Expected: All pass — no regressions.

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/ops add app/src/main/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptor.java app/src/test/java/io/casehub/ops/app/case_/ScalingEventCaseDescriptorTest.java app/src/main/java/io/casehub/ops/app/case_/CaseDefinitionRegistrar.java
git -C /Users/mdproctor/claude/casehub/ops commit -m "feat(#35): ScalingEventCaseDescriptor — evaluate/execute/verify workers, replaces scaling-event stub"
```

---

### Task 8: Deferred issues and final verification

**Files:**
- No code changes — GitHub issue creation and full build verification

- [ ] **Step 1: File deferred issues**

Create GitHub issues for deferred items:

1. **Scaling bounds** — `feat: scaling bounds (min/max replicas per service)` — needs ScalingPolicy model
2. **Cooldown period** — `feat: scaling cooldown period between events` — needs timestamp tracking in parent case
3. **Reactive 401 catch** — `feat: reactive credential refresh on 401 in K8s callers` — wire KubernetesActualStateAdapter/KubernetesNodeProvisioner to call refreshClient on auth failure
4. **Scaling trigger mechanism** — `feat: scaling trigger mechanism — what writes .scalingRequired/.scalingSpec to parent blackboard` — RAS, REST, monitoring integration

```bash
gh issue create --repo casehubio/casehub-ops --title "feat: scaling bounds — min/max replicas per service" --body "Needs ScalingPolicy model. Deferred from #35." --label enhancement
gh issue create --repo casehubio/casehub-ops --title "feat: scaling cooldown period between events" --body "Needs timestamp tracking in parent case. Deferred from #35." --label enhancement
gh issue create --repo casehubio/casehub-ops --title "feat: reactive credential refresh on 401 in K8s callers" --body "K8sClientRegistry provides refreshClient(). Wire KubernetesActualStateAdapter and KubernetesNodeProvisioner to call it on authentication failure. Deferred from #51." --label enhancement
gh issue create --repo casehubio/casehub-ops --title "feat: scaling trigger mechanism — what writes scalingRequired/scalingSpec" --body "Define who writes .scalingRequired and .scalingSpec to the parent application lifecycle case blackboard (RAS adapter, REST API, monitoring integration). Deferred from #35." --label enhancement
```

- [ ] **Step 2: Full build verification**

Run: `mvn --batch-mode -o test -pl app`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 3: Run ide_diagnostics on all changed files**

Verify no compilation errors or warnings across all modified files.
