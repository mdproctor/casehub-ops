# Drift Remediation, Credential Resolver, FaultPolicy Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #36 — ops-console: drift-remediation child case — full implementation
**Issue group:** #36, #44, #42

**Goal:** Close #42 (already done), wire credential resolver into K8s client creation (#44),
build case model infrastructure with working drift-remediation child case (#36).

**Architecture:** The app module implements the desiredstate SPI quad directly (not via domain
modules). Case model uses engine's CaseDefinition/Binding/Worker/SubCase APIs. Drift events
flow via CloudEvent observers (`@ObservesAsync CloudEvent`), matching the established pattern
in `DeploymentOutcomeTracker` and `DecommissionCompletionHandler`. Field-level drift diffs
are produced by refactoring existing `K8sDeploymentHandler.managedFieldsMatch()` into a
structured `readDiff()` method.

**Tech Stack:** Java 22, Quarkus 3.32, casehub-engine-api 0.2-SNAPSHOT, casehub-desiredstate-api
0.2-SNAPSHOT, casehub-platform-api 0.2-SNAPSHOT, fabric8 kubernetes-client, CloudEvents SDK,
JUnit 5, AssertJ.

## Global Constraints

- All source file edits use IntelliJ MCP (`ide_edit_member`, `ide_replace_member`, `ide_insert_member`)
- All code navigation uses IntelliJ MCP (`ide_find_references`, `ide_find_class`, `ide_search_text`)
- JQ expressions in `ContextChangeTrigger` require leading `.` (e.g., `.driftDetected`)
- Worker functions use `WorkerFunction.Sync<>(Map.class, fn)` pattern with `WorkerResult.of(output)`
- CloudEvent observers use `@ObservesAsync CloudEvent` with `DesiredStateEventTypes.*` type filtering
- `CaseDefinitionRegistry.registerCaseDefinition()` returns `Uni<CaseMetaModel>` — reactive
- Pre-release: no backward compatibility constraints. Breaking changes are fine.
- Package for case infrastructure: `io.casehub.ops.app.case_` (trailing underscore — `case` is a Java keyword)
- `ApplicationEntity.engineCaseId` already exists (nullable UUID). No migration needed for case ID.

---

### Task 1: Close #42 — FaultPolicy migration already complete

**Files:**
- No code changes

- [ ] **Step 1: Close the issue**

```bash
gh issue close 42 --repo casehubio/casehub-ops --comment "Already complete — all four domain FaultPolicy implementations (ComplianceFaultPolicy, DeploymentFaultPolicy, InfraFaultPolicy, IoTFaultPolicy) and their tests already use the 3-arg onFault(FaultEvent, DesiredStateGraph, ActualState) signature. Fixed in commits on #50 and prior work."
```

---

### Task 2: Credential resolver wiring (#44)

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java`
- Modify: `app/src/main/java/io/casehub/ops/app/entity/ClusterReferenceEntity.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/StartupRecoveryService.java`
- Modify: `app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java`
- Modify: `app/src/test/java/io/casehub/ops/app/service/StartupRecoveryServiceTest.java`
- Create: `app/src/main/resources/db/app/migration/V4__cluster_trust_certs.sql`

**Interfaces:**
- Consumes: `CredentialResolver` from `casehub-platform-api` (on classpath)
- Produces: `K8sClientRegistry.register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts)` — used by Tasks 7, 9

#### Step 2.1: Write V4 Flyway migration

- [ ] **Create migration file**

```sql
-- V4__cluster_trust_certs.sql
ALTER TABLE cluster_reference ADD COLUMN trust_certs BOOLEAN NOT NULL DEFAULT true;
```

Write to `app/src/main/resources/db/app/migration/V4__cluster_trust_certs.sql`.

#### Step 2.2: Add trustCerts to ClusterReferenceEntity

- [ ] **Add field to entity**

Use `ide_insert_member` to add after the `tenancyId` field:

```java
@Column(name = "trust_certs", nullable = false)
public boolean trustCerts = true;
```

#### Step 2.3: Write failing tests for K8sClientRegistry

- [ ] **Write credential resolver tests**

Rewrite `K8sClientRegistryTest` to test the new `register()` signature. The existing test
creates a `K8sClientRegistry` directly — it will need a mock `CredentialResolver`.

Tests to add:
1. `registerWithBearerToken` — credentialRef resolves to `{bearer-token: "tok123"}`, verify
   client config has oauthToken
2. `registerWithUserPassword` — credentialRef resolves to `{user: "admin", password: "secret"}`,
   verify client config has username/password
3. `registerWithEmptyMapLogsWarning` — non-blank credentialRef resolves to empty map, verify
   WARNING logged, client still created (auto-detection fallback)
4. `registerWithNullCredentialRefSkipsResolution` — null credentialRef, CredentialResolver not called
5. `registerWithTrustCertsFalse` — verify client config has trustCerts=false
6. `registerLegacyTwoArgDelegatesToFourArg` — 2-arg overload delegates with null/true

Each test constructs a `K8sClientRegistry` with a stub `CredentialResolver` and verifies the
fabric8 `Config` on the created client.

```java
class K8sClientRegistryTest {

    private K8sClientRegistry registry;

    @AfterEach
    void cleanup() { registry.shutdown(); }

    @Test
    void registerWithBearerToken() {
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok123");
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", "prod-creds", false);

        var client = registry.clientFor("c1");
        assertThat(client.getConfiguration().getOauthToken()).isEqualTo("tok123");
        assertThat(client.getConfiguration().isTrustCerts()).isFalse();
    }

    @Test
    void registerWithUserPassword() {
        CredentialResolver resolver = ref -> Map.of("user", "admin", "password", "secret");
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", "dev-creds", true);

        var client = registry.clientFor("c1");
        assertThat(client.getConfiguration().getUsername()).isEqualTo("admin");
        assertThat(client.getConfiguration().getPassword()).isEqualTo("secret");
    }

    @Test
    void registerWithNullCredentialRefSkipsResolution() {
        var called = new java.util.concurrent.atomic.AtomicBoolean(false);
        CredentialResolver resolver = ref -> { called.set(true); return Map.of(); };
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", null, true);

        assertThat(called.get()).isFalse();
        assertThat(registry.clientFor("c1")).isNotNull();
    }

    @Test
    void registerLegacyTwoArgDelegates() {
        CredentialResolver resolver = ref -> Map.of();
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443");

        var client = registry.clientFor("c1");
        assertThat(client).isNotNull();
        assertThat(client.getConfiguration().isTrustCerts()).isTrue();
    }

    @Test
    void clientForUnknownClusterThrows() {
        registry = new K8sClientRegistry(ref -> Map.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("unknown"));
    }
}
```

- [ ] **Run tests — expect FAIL** (register signature doesn't match yet)

```bash
mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest
```

#### Step 2.4: Implement K8sClientRegistry changes

- [ ] **Add CredentialResolver injection and new register() method**

Use `ide_edit_member` to rewrite `K8sClientRegistry`:

```java
@ApplicationScoped
public class K8sClientRegistry {

    private static final Logger LOG = Logger.getLogger(K8sClientRegistry.class.getName());

    private final ConcurrentHashMap<String, KubernetesClient> clients = new ConcurrentHashMap<>();
    private final CredentialResolver credentialResolver;

    @Inject
    public K8sClientRegistry(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    public KubernetesClient clientFor(String clusterId) {
        KubernetesClient client = clients.get(clusterId);
        if (client == null) {
            throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
        }
        return client;
    }

    public void register(String clusterId, String apiUrl) {
        register(clusterId, apiUrl, null, true);
    }

    public void register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts) {
        var config = new ConfigBuilder()
                .withMasterUrl(apiUrl)
                .withTrustCerts(trustCerts)
                .build();

        if (credentialRef != null && !credentialRef.isBlank()) {
            Map<String, String> creds = credentialResolver.resolve(credentialRef);
            if (creds.isEmpty()) {
                LOG.warning("Credential reference '" + credentialRef
                        + "' resolved to empty map — possible misconfiguration. Falling back to auto-detection.");
            } else {
                applyCredentials(config, creds);
            }
        }

        KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(config)
                .build();
        KubernetesClient existing = clients.putIfAbsent(clusterId, client);
        if (existing != null) {
            client.close();
        }
    }

    private void applyCredentials(Config config, Map<String, String> creds) {
        String bearerToken = creds.get("bearer-token");
        if (bearerToken != null) {
            config.setOauthToken(bearerToken);
            return;
        }
        String user = creds.get("user");
        String password = creds.get("password");
        if (user != null && password != null) {
            config.setUsername(user);
            config.setPassword(password);
            return;
        }
        String apiKey = creds.get("api-key");
        if (apiKey != null) {
            config.setCustomHeaders(Map.of("Authorization", "ApiKey " + apiKey));
        }
    }

    public void deregister(String clusterId) {
        KubernetesClient client = clients.remove(clusterId);
        if (client != null) { client.close(); }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(KubernetesClient::close);
        clients.clear();
    }
}
```

- [ ] **Run tests — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest
```

#### Step 2.5: Update call sites

- [ ] **Update ApplicationLifecycleService.deploy()** to pass credentialRef+trustCerts

In `deploy()`, change `clientRegistry.register(cluster.id.toString(), cluster.apiUrl)` to:

```java
clientRegistry.register(cluster.id.toString(), cluster.apiUrl, cluster.credentialRef, cluster.trustCerts);
```

- [ ] **Update StartupRecoveryService** — change `BiConsumer<String, String>` to 4-arg interface

The `clusterRegistrar` field type changes from `BiConsumer<String, String>` to a new functional
interface. Define an inner `@FunctionalInterface`:

```java
@FunctionalInterface
interface ClusterRegistrar {
    void register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts);
}
```

Update the CDI constructor:
```java
this.clusterRegistrar = (id, url, cred, trust) -> clientRegistry.register(id, url, cred, trust);
```

Update the test constructor to accept `ClusterRegistrar` instead of `BiConsumer<String, String>`.

Update `recover()` to call:
```java
clusterRegistrar.register(cluster.id.toString(), cluster.apiUrl, cluster.credentialRef, cluster.trustCerts);
```

- [ ] **Update StartupRecoveryServiceTest** — change recording stub

The `RecordingClientRegistry` changes from `register(String, String)` to
`register(String, String, String, boolean)`. Update all test assertions accordingly.

- [ ] **Run full test suite**

```bash
mvn --batch-mode -o test -pl app
```

#### Step 2.6: Commit

```bash
git add app/
git commit -m "feat(#44): wire CredentialResolver into K8sClientRegistry

Inject platform CredentialResolver SPI. register() now accepts credentialRef
and trustCerts. Supports bearer-token, user/password, and api-key credentials.
Logs warning for misconfigured refs (non-blank ref resolving to empty map).
trustCerts now per-cluster instead of hardcoded true.

Closes #44"
```

---

### Task 3: Drift model records

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/model/FieldDrift.java`
- Create: `app/src/main/java/io/casehub/ops/app/model/NodeDrift.java`
- Create: `app/src/main/java/io/casehub/ops/app/model/DriftReport.java`
- Create: `app/src/test/java/io/casehub/ops/app/model/DriftReportTest.java`

**Interfaces:**
- Produces: `FieldDrift(String fieldName, String expectedValue, String actualValue)`,
  `NodeDrift(String nodeId, List<FieldDrift> fields)`,
  `DriftReport(List<NodeDrift> driftDetails, String clusterId, String applicationId, Instant detectedAt, int consecutiveDriftCount)`
  — used by Tasks 4, 5, 7, 8

#### Step 3.1: Write failing tests for DriftReport

- [ ] **Write tests**

```java
package io.casehub.ops.app.model;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DriftReportTest {

    @Test
    void driftedNodeIdsExtractsFromDetails() {
        var report = new DriftReport(
                List.of(new NodeDrift("node-1", List.of()), new NodeDrift("node-2", List.of())),
                "cluster-1", "app-1", Instant.now(), 1);
        assertThat(report.driftedNodeIds()).containsExactly("node-1", "node-2");
    }

    @Test
    void emptyDetailsReturnsEmptyNodeIds() {
        var report = new DriftReport(List.of(), "c1", "a1", Instant.now(), 0);
        assertThat(report.driftedNodeIds()).isEmpty();
    }

    @Test
    void hasSecuritySensitiveFieldsDetectsImage() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("image", "v1", "v2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void hasSecuritySensitiveFieldsDetectsServiceAccount() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("serviceAccount", "sa1", "sa2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void nonSecurityFieldsReturnsFalse() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("replicas", "3", "2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isFalse();
    }

    @Test
    void noFieldsReturnsFalse() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of())),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isFalse();
    }
}
```

- [ ] **Run — expect FAIL**

```bash
mvn --batch-mode -o test -pl app -Dtest=DriftReportTest
```

#### Step 3.2: Create the three records

- [ ] **Create FieldDrift.java**

```java
package io.casehub.ops.app.model;

public record FieldDrift(String fieldName, String expectedValue, String actualValue) {}
```

- [ ] **Create NodeDrift.java**

```java
package io.casehub.ops.app.model;

import java.util.List;

public record NodeDrift(String nodeId, List<FieldDrift> fields) {}
```

- [ ] **Create DriftReport.java**

```java
package io.casehub.ops.app.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record DriftReport(
        List<NodeDrift> driftDetails,
        String clusterId,
        String applicationId,
        Instant detectedAt,
        int consecutiveDriftCount) {

    private static final Set<String> SECURITY_FIELDS =
            Set.of("image", "serviceAccount", "rbac", "secrets");

    public List<String> driftedNodeIds() {
        return driftDetails.stream().map(NodeDrift::nodeId).toList();
    }

    public boolean hasSecuritySensitiveFields() {
        return driftDetails.stream()
                .flatMap(nd -> nd.fields().stream())
                .anyMatch(f -> SECURITY_FIELDS.contains(f.fieldName()));
    }
}
```

- [ ] **Run — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=DriftReportTest
```

#### Step 3.3: Commit

```bash
git add app/
git commit -m "feat(#36): add FieldDrift, NodeDrift, DriftReport model records

Foundation data model for drift classification. DriftReport carries per-node
field-level diffs with convenience methods for node ID extraction and
security-sensitive field detection."
```

---

### Task 4: K8sResourceHandler.readDiff() and K8sDriftDiffService

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/K8sResourceHandler.java`
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/K8sDeploymentHandler.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sDriftDiffService.java`
- Create: `app/src/test/java/io/casehub/ops/app/k8s/K8sDeploymentHandlerDiffTest.java`
- Create: `app/src/test/java/io/casehub/ops/app/k8s/K8sDriftDiffServiceTest.java`

**Interfaces:**
- Consumes: `FieldDrift`, `NodeDrift` from Task 3
- Produces: `K8sResourceHandler.readDiff(KubernetesClient, S spec) → List<FieldDrift>`,
  `K8sDriftDiffService.computeDiff(String nodeId, String compositeKey) → List<FieldDrift>`
  — used by Task 7

#### Step 4.1: Add readDiff() default method to K8sResourceHandler

- [ ] **Add default method to interface**

Use `ide_insert_member` on `K8sResourceHandler` to add after `readStatus`:

```java
default List<FieldDrift> readDiff(KubernetesClient client, S spec) {
    return List.of();
}
```

Add import for `io.casehub.ops.app.model.FieldDrift` and `java.util.List`.

#### Step 4.2: Write failing tests for K8sDeploymentHandler.readDiff()

- [ ] **Write diff tests**

```java
package io.casehub.ops.app.k8s;

import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.app.model.FieldDrift;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class K8sDeploymentHandlerDiffTest {

    private KubernetesMockServer mockServer;
    private KubernetesClient client;
    private final K8sDeploymentHandler handler = new K8sDeploymentHandler();

    @BeforeEach
    void setUp() {
        mockServer = new KubernetesMockServer(false);
        mockServer.init();
        client = mockServer.createClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
        mockServer.destroy();
    }

    @Test
    void noDriftReturnsEmptyList() {
        var spec = makeSpec("my-deploy", "ns", "nginx:1.25", 3);
        var resource = handler.toResource(spec);
        client.resource(resource).createOrReplace();

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).isEmpty();
    }

    @Test
    void replicasDriftReturnsFieldDrift() {
        var spec = makeSpec("my-deploy", "ns", "nginx:1.25", 3);
        var resource = handler.toResource(spec);
        // Deploy with different replicas
        var modified = new DeploymentBuilder(resource).editSpec().withReplicas(5).endSpec().build();
        client.resource(modified).createOrReplace();

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).fieldName()).isEqualTo("replicas");
        assertThat(diffs.get(0).expectedValue()).isEqualTo("3");
        assertThat(diffs.get(0).actualValue()).isEqualTo("5");
    }

    @Test
    void imageDriftReturnsFieldDrift() {
        var spec = makeSpec("my-deploy", "ns", "nginx:1.25", 2);
        var resource = handler.toResource(spec);
        var modified = new DeploymentBuilder(resource)
                .editSpec().editTemplate().editSpec()
                .editFirstContainer().withImage("nginx:1.24").endContainer()
                .endSpec().endTemplate().endSpec().build();
        client.resource(modified).createOrReplace();

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).anyMatch(d -> d.fieldName().equals("image"));
    }

    @Test
    void absentResourceReturnsEmptyList() {
        var spec = makeSpec("nonexistent", "ns", "nginx:1.25", 1);
        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).isEmpty();
    }

    private K8sDeploymentSpec makeSpec(String name, String ns, String image, int replicas) {
        return new K8sDeploymentSpec(ns, name, image, replicas,
                new io.casehub.ops.api.infra.K8sResourceRequirements("100m", "128Mi", "500m", "256Mi"),
                new io.casehub.ops.api.infra.K8sLabels(Map.of("app", name)),
                List.of(), Map.of(), java.util.Optional.empty());
    }
}
```

- [ ] **Run — expect FAIL** (readDiff returns empty list for drifted resources)

```bash
mvn --batch-mode -o test -pl app -Dtest=K8sDeploymentHandlerDiffTest
```

#### Step 4.3: Implement K8sDeploymentHandler.readDiff()

- [ ] **Refactor managedFieldsMatch into readDiff**

The existing `managedFieldsMatch()` private method compares fields and returns boolean.
Refactor to produce `List<FieldDrift>` instead. Keep `readStatus()` using the new method:
`readStatus` calls `readDiff` and returns DRIFTED if non-empty.

Use `ide_edit_member` on `K8sDeploymentHandler` to replace `readStatus` and `managedFieldsMatch`:

```java
@Override
public NodeStatus readStatus(KubernetesClient client, K8sDeploymentSpec spec) {
    try {
        var actual = client.apps().deployments()
                .inNamespace(spec.namespace()).withName(spec.name()).get();
        if (actual == null) return NodeStatus.ABSENT;
        return readDiff(client, spec).isEmpty() ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    } catch (KubernetesClientException e) {
        return NodeStatus.UNKNOWN;
    }
}

@Override
public List<FieldDrift> readDiff(KubernetesClient client, K8sDeploymentSpec spec) {
    try {
        var actual = client.apps().deployments()
                .inNamespace(spec.namespace()).withName(spec.name()).get();
        if (actual == null) return List.of();

        var desired = (Deployment) toResource(spec);
        return computeDiffs(actual, desired);
    } catch (KubernetesClientException e) {
        return List.of();
    }
}

private List<FieldDrift> computeDiffs(Deployment actual, Deployment desired) {
    var diffs = new ArrayList<FieldDrift>();
    var ac = actual.getSpec().getTemplate().getSpec().getContainers().get(0);
    var dc = desired.getSpec().getTemplate().getSpec().getContainers().get(0);

    if (!Objects.equals(actual.getSpec().getReplicas(), desired.getSpec().getReplicas())) {
        diffs.add(new FieldDrift("replicas",
                String.valueOf(desired.getSpec().getReplicas()),
                String.valueOf(actual.getSpec().getReplicas())));
    }
    if (!Objects.equals(ac.getImage(), dc.getImage())) {
        diffs.add(new FieldDrift("image", dc.getImage(), ac.getImage()));
    }

    Map<String, String> actualEnv = ac.getEnv() == null ? Map.of()
            : ac.getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    Map<String, String> desiredEnv = dc.getEnv() == null ? Map.of()
            : dc.getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
    if (!actualEnv.equals(desiredEnv)) {
        diffs.add(new FieldDrift("env", desiredEnv.toString(), actualEnv.toString()));
    }

    if (!Objects.equals(ac.getResources().getRequests(), dc.getResources().getRequests())) {
        diffs.add(new FieldDrift("resourceRequests",
                String.valueOf(dc.getResources().getRequests()),
                String.valueOf(ac.getResources().getRequests())));
    }
    if (!Objects.equals(ac.getResources().getLimits(), dc.getResources().getLimits())) {
        diffs.add(new FieldDrift("resourceLimits",
                String.valueOf(dc.getResources().getLimits()),
                String.valueOf(ac.getResources().getLimits())));
    }

    if (!portsMatch(ac.getPorts(), dc.getPorts())) {
        diffs.add(new FieldDrift("ports",
                String.valueOf(dc.getPorts()), String.valueOf(ac.getPorts())));
    }

    if (!Objects.equals(ac.getLivenessProbe(), dc.getLivenessProbe())) {
        diffs.add(new FieldDrift("livenessProbe",
                String.valueOf(dc.getLivenessProbe()), String.valueOf(ac.getLivenessProbe())));
    }
    if (!Objects.equals(ac.getReadinessProbe(), dc.getReadinessProbe())) {
        diffs.add(new FieldDrift("readinessProbe",
                String.valueOf(dc.getReadinessProbe()), String.valueOf(ac.getReadinessProbe())));
    }

    return List.copyOf(diffs);
}

private boolean portsMatch(List<?> actual, List<?> desired) {
    if (actual == null && desired == null) return true;
    if (actual == null || desired == null) return false;
    if (actual.size() != desired.size()) return false;
    for (int i = 0; i < actual.size(); i++) {
        var ap = (io.fabric8.kubernetes.api.model.ContainerPort) actual.get(i);
        var dp = (io.fabric8.kubernetes.api.model.ContainerPort) desired.get(i);
        if (!Objects.equals(ap.getContainerPort(), dp.getContainerPort())) return false;
        if (!Objects.equals(ap.getProtocol(), dp.getProtocol())) return false;
    }
    return true;
}
```

Remove the old `managedFieldsMatch()` method.

- [ ] **Run tests — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=K8sDeploymentHandlerDiffTest
```

- [ ] **Run existing readStatus tests to verify no regression**

```bash
mvn --batch-mode -o test -pl app -Dtest=KubernetesActualStateAdapterTest
```

#### Step 4.4: Commit

```bash
git add app/
git commit -m "feat(#36): add readDiff() to K8sResourceHandler — field-level drift detection

Refactors K8sDeploymentHandler.managedFieldsMatch() into readDiff() that returns
List<FieldDrift>. readStatus() now delegates to readDiff(). Supports diff detection
for replicas, image, env, resources, ports, and probes."
```

---

### Task 5: Case descriptors

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/case_/ApplicationCaseDescriptor.java`
- Create: `app/src/main/java/io/casehub/ops/app/case_/StubChildCaseDescriptor.java`
- Create: `app/src/main/java/io/casehub/ops/app/case_/DriftRemediationCaseDescriptor.java`
- Create: `app/src/test/java/io/casehub/ops/app/case_/ApplicationCaseDescriptorTest.java`
- Create: `app/src/test/java/io/casehub/ops/app/case_/DriftRemediationCaseDescriptorTest.java`

**Interfaces:**
- Consumes: `DriftReport`, `FieldDrift`, `NodeDrift` from Task 3; engine APIs
  (`CaseDefinition`, `Binding`, `SubCase`, `ContextChangeTrigger`, `Capability`, `Worker`,
  `WorkerFunction`, `WorkerResult`)
- Produces: `ApplicationCaseDescriptor.build() → CaseDefinition`,
  `DriftRemediationCaseDescriptor.build() → CaseDefinition`,
  `StubChildCaseDescriptor.build(ns, name, version) → CaseDefinition`
  — used by Task 6

#### Step 5.1: Write failing tests for ApplicationCaseDescriptor

- [ ] **Write tests**

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.SubCaseTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApplicationCaseDescriptorTest {

    @Test
    void buildReturnsCorrectIdentity() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        assertThat(def.getNamespace()).isEqualTo("ops");
        assertThat(def.getName()).isEqualTo("application-lifecycle");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void hasSixBindings() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        assertThat(def.getBindings()).hasSize(6);
    }

    @Test
    void driftBindingHasCorrectTriggerAndTarget() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        Binding driftBinding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-drift-detected"))
                .findFirst().orElseThrow();

        assertThat(driftBinding.getOn()).isInstanceOf(ContextChangeTrigger.class);
        var trigger = (ContextChangeTrigger) driftBinding.getOn();
        assertThat(trigger.getFilter().toString()).contains(".driftDetected");

        assertThat(driftBinding.target()).isInstanceOf(SubCaseTarget.class);
        var subCaseTarget = (SubCaseTarget) driftBinding.target();
        assertThat(subCaseTarget.subCase().namespace()).isEqualTo("ops");
        assertThat(subCaseTarget.subCase().name()).isEqualTo("drift-remediation");
    }

    @Test
    void allBindingsUseJqDotPrefix() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        for (Binding binding : def.getBindings()) {
            var trigger = (ContextChangeTrigger) binding.getOn();
            assertThat(trigger.getFilter().toString())
                    .as("Binding '%s' trigger must use JQ dot prefix", binding.getName())
                    .startsWith(".");
        }
    }
}
```

- [ ] **Run — expect FAIL**

```bash
mvn --batch-mode -o test -pl app -Dtest=ApplicationCaseDescriptorTest
```

#### Step 5.2: Implement ApplicationCaseDescriptor

- [ ] **Create ApplicationCaseDescriptor.java**

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.SubCase;

import java.util.List;

public final class ApplicationCaseDescriptor {

    private ApplicationCaseDescriptor() {}

    public static CaseDefinition build() {
        return CaseDefinition.builder()
                .namespace("ops")
                .name("application-lifecycle")
                .version("1.0")
                .title("Application Lifecycle")
                .summary("Long-lived case managing a deployed application")
                .bindings(bindings())
                .build();
    }

    private static List<Binding> bindings() {
        return List.of(
                childCaseBinding("on-drift-detected", ".driftDetected",
                        "ops", "drift-remediation", "1.0", ".driftDetected"),
                childCaseBinding("on-cve-detected", ".cveDetected",
                        "ops", "cve-response", "1.0", ".cveData"),
                childCaseBinding("on-upgrade-requested", ".upgradeRequested",
                        "ops", "service-upgrade", "1.0", ".upgradeSpec"),
                childCaseBinding("on-incident-detected", ".incidentDetected",
                        "ops", "incident-response", "1.0", ".incidentData"),
                childCaseBinding("on-scaling-required", ".scalingRequired",
                        "ops", "scaling-event", "1.0", ".scalingSpec"),
                childCaseBinding("on-compliance-violation", ".complianceViolation",
                        "ops", "compliance-remediation", "1.0", ".violationData"));
    }

    private static Binding childCaseBinding(String name, String triggerFilter,
                                             String childNs, String childName,
                                             String childVersion, String inputMapping) {
        return Binding.builder()
                .name(name)
                .on(new ContextChangeTrigger(triggerFilter))
                .subCase(SubCase.builder()
                        .namespace(childNs)
                        .name(childName)
                        .version(childVersion)
                        .inputMapping(inputMapping)
                        .waitForCompletion(false)
                        .build())
                .build();
    }
}
```

- [ ] **Run — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=ApplicationCaseDescriptorTest
```

#### Step 5.3: Implement StubChildCaseDescriptor

- [ ] **Create StubChildCaseDescriptor.java**

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;

public final class StubChildCaseDescriptor {

    private StubChildCaseDescriptor() {}

    public static CaseDefinition build(String namespace, String name, String version) {
        String capabilityName = name + "-stub";
        return CaseDefinition.builder()
                .namespace(namespace)
                .name(name)
                .version(version)
                .title(name + " (stub)")
                .capabilities(Capability.of(capabilityName, "any", "any"))
                .workers(Worker.builder()
                        .name(name + "-stub-worker")
                        .capabilityName(capabilityName)
                        .function(new WorkerFunction.Sync<>(Map.class,
                                input -> WorkerResult.of(Map.of("status", "stub"))))
                        .build())
                .build();
    }
}
```

#### Step 5.4: Write failing tests for DriftRemediationCaseDescriptor

- [ ] **Write tests**

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DriftRemediationCaseDescriptorTest {

    @Test
    void buildReturnsCorrectIdentity() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getNamespace()).isEqualTo("ops");
        assertThat(def.getName()).isEqualTo("drift-remediation");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void hasThreeCapabilities() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getCapabilities()).hasSize(3);
        assertThat(def.getCapabilities()).extracting("name")
                .containsExactlyInAnyOrder("classify-drift", "remediate-drift", "escalate-drift");
    }

    @Test
    void hasThreeWorkers() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getWorkers()).hasSize(3);
    }

    @Test
    void hasTwoInternalBindings() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getBindings()).hasSize(2);
    }

    @Test
    void classificationBindingTriggersOnDriftClassification() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        Binding binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-classification-complete"))
                .findFirst().orElseThrow();
        var trigger = (ContextChangeTrigger) binding.getOn();
        assertThat(trigger.getFilter().toString()).contains(".driftClassification");
    }

    @Test
    void escalationBindingTriggersOnEscalationRequired() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        Binding binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-escalation-required"))
                .findFirst().orElseThrow();
        var trigger = (ContextChangeTrigger) binding.getOn();
        assertThat(trigger.getFilter().toString()).contains(".escalationRequired");
    }

    @Test
    void hasCompletionPredicate() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getCompletion()).isNotNull();
    }
}
```

- [ ] **Run — expect FAIL**

```bash
mvn --batch-mode -o test -pl app -Dtest=DriftRemediationCaseDescriptorTest
```

#### Step 5.5: Implement DriftRemediationCaseDescriptor

- [ ] **Create DriftRemediationCaseDescriptor.java**

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.ops.app.model.DriftReport;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DriftRemediationCaseDescriptor {

    private DriftRemediationCaseDescriptor() {}

    public static CaseDefinition build() {
        return CaseDefinition.builder()
                .namespace("ops")
                .name("drift-remediation")
                .version("1.0")
                .title("Drift Remediation")
                .summary("Classifies, remediates, and optionally escalates detected drift")
                .capabilities(capabilities())
                .workers(workers())
                .bindings(bindings())
                .completion(".remediationStatus == \"converged\"")
                .build();
    }

    private static List<Capability> capabilities() {
        return List.of(
                Capability.of("classify-drift", "any", "any"),
                Capability.of("remediate-drift", "any", "any"),
                Capability.of("escalate-drift", "any", "any"));
    }

    @SuppressWarnings("unchecked")
    private static List<Worker> workers() {
        return List.of(
                Worker.builder()
                        .name("drift-classify-worker")
                        .capabilityName("classify-drift")
                        .function(new WorkerFunction.Sync<>(Map.class,
                                DriftRemediationCaseDescriptor::classifyDrift))
                        .build(),
                Worker.builder()
                        .name("drift-remediate-worker")
                        .capabilityName("remediate-drift")
                        .function(new WorkerFunction.Sync<>(Map.class,
                                DriftRemediationCaseDescriptor::remediateDrift))
                        .build(),
                Worker.builder()
                        .name("drift-escalate-worker")
                        .capabilityName("escalate-drift")
                        .function(new WorkerFunction.Sync<>(Map.class,
                                DriftRemediationCaseDescriptor::escalateDrift))
                        .build());
    }

    private static List<Binding> bindings() {
        return List.of(
                Binding.builder()
                        .name("on-classification-complete")
                        .on(new ContextChangeTrigger(".driftClassification"))
                        .capability(Capability.of("remediate-drift", "any", "any"))
                        .build(),
                Binding.builder()
                        .name("on-escalation-required")
                        .on(new ContextChangeTrigger(".escalationRequired"))
                        .capability(Capability.of("escalate-drift", "any", "any"))
                        .build());
    }

    static WorkerResult classifyDrift(Map<String, Object> input) {
        int consecutiveDriftCount = input.containsKey("consecutiveDriftCount")
                ? ((Number) input.get("consecutiveDriftCount")).intValue() : 1;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftDetails = (List<Map<String, Object>>) input.getOrDefault("driftDetails", List.of());

        boolean persistent = consecutiveDriftCount > 1;
        boolean multiNode = driftDetails.size() > 1;
        boolean securitySensitive = driftDetails.stream()
                .flatMap(nd -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> fields = (List<Map<String, Object>>) nd.getOrDefault("fields", List.of());
                    return fields.stream();
                })
                .anyMatch(f -> List.of("image", "serviceAccount", "rbac", "secrets")
                        .contains(f.get("fieldName")));

        boolean critical = persistent || securitySensitive || multiNode;
        String severity = critical ? "critical" : "benign";

        var reasons = new java.util.ArrayList<String>();
        if (persistent) reasons.add("persistent drift (consecutive count: " + consecutiveDriftCount + ")");
        if (securitySensitive) reasons.add("security-sensitive fields changed");
        if (multiNode) reasons.add("multiple nodes drifted (" + driftDetails.size() + ")");
        if (reasons.isEmpty()) reasons.add("single-node, first occurrence, non-security fields");

        List<String> nodeIds = driftDetails.stream()
                .map(nd -> (String) nd.get("nodeId"))
                .toList();

        var classification = new LinkedHashMap<String, Object>();
        classification.put("severity", severity);
        classification.put("reason", String.join("; ", reasons));
        classification.put("nodeIds", nodeIds);

        return WorkerResult.of(Map.of("driftClassification", classification));
    }

    static WorkerResult remediateDrift(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> classification = (Map<String, Object>) input.get("driftClassification");
        String severity = classification != null ? (String) classification.get("severity") : "benign";

        if ("critical".equals(severity)) {
            return WorkerResult.of(Map.of("escalationRequired", true));
        }
        return WorkerResult.of(Map.of("remediationStatus", "auto-remediating"));
    }

    static WorkerResult escalateDrift(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> classification = (Map<String, Object>) input.getOrDefault("driftClassification", Map.of());
        @SuppressWarnings("unchecked")
        List<String> nodeIds = (List<String>) classification.getOrDefault("nodeIds", List.of());
        String reason = (String) classification.getOrDefault("reason", "unknown");

        return WorkerResult.of(Map.of(
                "escalation", Map.of(
                        "summary", "Persistent drift detected on " + nodeIds.size() + " node(s)",
                        "detail", reason,
                        "nodeIds", nodeIds,
                        "risk", "HIGH")));
    }
}
```

- [ ] **Run tests — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=DriftRemediationCaseDescriptorTest
```

#### Step 5.6: Write worker logic unit tests

- [ ] **Create DriftClassifyWorkerTest.java**

```java
package io.casehub.ops.app.case_;

import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DriftClassifyWorkerTest {

    @Test
    void singleNodeFirstOccurrenceNonSecurityIsBenign() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of(Map.of("nodeId", "n1",
                        "fields", List.of(Map.of("fieldName", "replicas", "expectedValue", "3", "actualValue", "2")))));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("benign");
    }

    @Test
    void persistentDriftIsCritical() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 3,
                "driftDetails", List.of(Map.of("nodeId", "n1", "fields", List.of())));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("critical");
        assertThat((String) classification.get("reason")).contains("persistent");
    }

    @Test
    void securitySensitiveFieldIsCritical() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of(Map.of("nodeId", "n1",
                        "fields", List.of(Map.of("fieldName", "image", "expectedValue", "v1", "actualValue", "v2")))));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("critical");
        assertThat((String) classification.get("reason")).contains("security");
    }

    @Test
    void multiNodeDriftIsCritical() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of(
                        Map.of("nodeId", "n1", "fields", List.of()),
                        Map.of("nodeId", "n2", "fields", List.of())));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("critical");
    }

    @Test
    void emptyDetailsIsBenign() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of());

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("benign");
    }

    @Test
    void remediateWorkerBenignReturnsAutoRemediating() {
        var input = Map.<String, Object>of(
                "driftClassification", Map.of("severity", "benign"));

        WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input);
        assertThat(result.output().get("remediationStatus")).isEqualTo("auto-remediating");
    }

    @Test
    void remediateWorkerCriticalSetsEscalation() {
        var input = Map.<String, Object>of(
                "driftClassification", Map.of("severity", "critical"));

        WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input);
        assertThat(result.output().get("escalationRequired")).isEqualTo(true);
    }

    @Test
    void escalateWorkerProducesEscalationOutput() {
        var input = Map.<String, Object>of(
                "driftClassification", Map.of(
                        "nodeIds", List.of("n1", "n2"),
                        "reason", "persistent drift"));

        WorkerResult result = DriftRemediationCaseDescriptor.escalateDrift(input);

        @SuppressWarnings("unchecked")
        var escalation = (Map<String, Object>) result.output().get("escalation");
        assertThat(escalation.get("risk")).isEqualTo("HIGH");
        assertThat((String) escalation.get("summary")).contains("2 node(s)");
    }
}
```

- [ ] **Run all case descriptor tests — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest="ApplicationCaseDescriptorTest,DriftRemediationCaseDescriptorTest,DriftClassifyWorkerTest"
```

#### Step 5.7: Commit

```bash
git add app/
git commit -m "feat(#36): add case descriptors — ApplicationCaseDescriptor, DriftRemediationCaseDescriptor, StubChildCaseDescriptor

ApplicationCaseDescriptor builds ops:application-lifecycle CaseDefinition with
6 context-change bindings (drift working, 5 stubbed).
DriftRemediationCaseDescriptor builds ops:drift-remediation with classify/remediate/escalate
workers and JQ-based completion predicate.
StubChildCaseDescriptor provides no-op CaseDefinition for non-drift child cases."
```

---

### Task 6: CaseDefinitionRegistrar

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/case_/CaseDefinitionRegistrar.java`
- Create: `app/src/test/java/io/casehub/ops/app/case_/CaseDefinitionRegistrarTest.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/StartupRecoveryService.java` (add `@Priority(20)`)

**Interfaces:**
- Consumes: `ApplicationCaseDescriptor.build()`, `DriftRemediationCaseDescriptor.build()`,
  `StubChildCaseDescriptor.build()`, `CaseDefinitionRegistry.registerCaseDefinition()`
- Produces: All 7 case definitions registered on startup — required before any case instance is created

#### Step 6.1: Write failing test

- [ ] **Write test**

```java
package io.casehub.ops.app.case_;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CaseDefinitionRegistrarTest {

    @Test
    void registersSevenCaseDefinitions() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry);

        registrar.onStartup(null);

        assertThat(registry.registered).hasSize(7);
    }

    @Test
    void registersApplicationLifecycleDefinition() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry);

        registrar.onStartup(null);

        assertThat(registry.registered).anyMatch(d ->
                "ops".equals(d.getNamespace()) && "application-lifecycle".equals(d.getName()));
    }

    @Test
    void registersDriftRemediationDefinition() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry);

        registrar.onStartup(null);

        assertThat(registry.registered).anyMatch(d ->
                "ops".equals(d.getNamespace()) && "drift-remediation".equals(d.getName()));
    }

    @Test
    void registersFiveStubDefinitions() {
        var registry = new RecordingRegistry();
        var registrar = new CaseDefinitionRegistrar(registry);

        registrar.onStartup(null);

        List<String> stubNames = registry.registered.stream()
                .filter(d -> d.getTitle() != null && d.getTitle().contains("stub"))
                .map(CaseDefinition::getName)
                .toList();
        assertThat(stubNames).containsExactlyInAnyOrder(
                "cve-response", "service-upgrade", "incident-response",
                "scaling-event", "compliance-remediation");
    }

    private static class RecordingRegistry implements CaseDefinitionRegistry {
        final List<CaseDefinition> registered = new ArrayList<>();

        @Override
        public Uni<CaseMetaModel> registerCaseDefinition(CaseDefinition model) {
            registered.add(model);
            return Uni.createFrom().nullItem();
        }

        @Override
        public CaseDefinition getCaseDefinition(CaseMetaModel definition) { return null; }

        @Override
        public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) { return null; }
    }
}
```

- [ ] **Run — expect FAIL**

```bash
mvn --batch-mode -o test -pl app -Dtest=CaseDefinitionRegistrarTest
```

#### Step 6.2: Implement CaseDefinitionRegistrar

- [ ] **Create CaseDefinitionRegistrar.java**

```java
package io.casehub.ops.app.case_;

import java.util.List;
import java.util.logging.Logger;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CaseDefinitionRegistrar {

    private static final Logger LOG = Logger.getLogger(CaseDefinitionRegistrar.class.getName());

    private final CaseDefinitionRegistry registry;

    @Inject
    public CaseDefinitionRegistrar(CaseDefinitionRegistry registry) {
        this.registry = registry;
    }

    void onStartup(@Observes @Priority(10) StartupEvent event) {
        List<CaseDefinition> definitions = List.of(
                ApplicationCaseDescriptor.build(),
                DriftRemediationCaseDescriptor.build(),
                StubChildCaseDescriptor.build("ops", "cve-response", "1.0"),
                StubChildCaseDescriptor.build("ops", "service-upgrade", "1.0"),
                StubChildCaseDescriptor.build("ops", "incident-response", "1.0"),
                StubChildCaseDescriptor.build("ops", "scaling-event", "1.0"),
                StubChildCaseDescriptor.build("ops", "compliance-remediation", "1.0"));

        for (CaseDefinition def : definitions) {
            registry.registerCaseDefinition(def)
                    .subscribe().with(
                            meta -> LOG.fine("Registered case definition: " + def.getNamespace() + ":" + def.getName()),
                            err -> LOG.warning("Failed to register " + def.getName() + ": " + err.getMessage()));
        }
        LOG.info("Registered " + definitions.size() + " case definitions");
    }
}
```

- [ ] **Add @Priority(20) to StartupRecoveryService.onStartup()**

Use `ide_edit_member` on `StartupRecoveryService.onStartup`:

```java
void onStartup(@Observes @Priority(20) StartupEvent event) {
    recover();
}
```

Add import `jakarta.annotation.Priority`.

- [ ] **Run tests — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=CaseDefinitionRegistrarTest
```

- [ ] **Verify StartupRecoveryServiceTest still passes**

```bash
mvn --batch-mode -o test -pl app -Dtest=StartupRecoveryServiceTest
```

#### Step 6.3: Commit

```bash
git add app/
git commit -m "feat(#36): add CaseDefinitionRegistrar — registers 7 case definitions on startup

@Startup @Priority(10) ensures definitions are registered before
StartupRecoveryService (@Priority(20)) re-starts reconciliation loops.
Registers: application-lifecycle, drift-remediation, and 5 stub child cases."
```

---

### Task 7: Drift signal bridge

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java`
- Modify: `app/src/test/java/io/casehub/ops/app/service/ApplicationLifecycleServiceTest.java`

**Interfaces:**
- Consumes: `DesiredStateEventTypes.NODE_DRIFTED`, `NodeDriftedData`, `CaseHubRuntime.signal()`,
  `K8sDriftDiffService` from Task 4, `DriftReport` from Task 3, case definitions from Task 5
- Produces: `onDriftEvent(@ObservesAsync CloudEvent)` — NODE_DRIFTED → case signal bridge,
  `DriftTracker` — consecutive drift count tracking — used by Task 8

#### Step 7.1: Write failing tests for drift signal bridge

- [ ] **Write tests** in a new test class (to keep existing tests untouched)

```java
package io.casehub.ops.app.service;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeDriftedData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class ApplicationLifecycleServiceDriftBridgeTest {

    // Test will verify:
    // 1. NODE_DRIFTED CloudEvent triggers case signal
    // 2. Consecutive drift count increments on overlap
    // 3. Consecutive drift count resets on disjoint set
    // 4. Non-DRIFTED events are ignored

    // These tests will be implemented against the actual CloudEvent observer method
    // once the implementation in Step 7.2 is complete.
}
```

Due to the complexity of the CloudEvent observer testing (requires recording stubs for
CaseHubRuntime, K8sDriftDiffService, ObjectMapper), implement the tests alongside the
implementation in Step 7.2.

#### Step 7.2: Implement drift observer and consecutive tracking

- [ ] **Add fields and methods to ApplicationLifecycleService**

Add to `ApplicationLifecycleService`:

1. Inject `ObjectMapper`, `CaseHubRuntime` (if not already available)
2. Add `DriftTracker` record and `ConcurrentHashMap<String, DriftTracker>` field
3. Add `onDriftEvent(@ObservesAsync CloudEvent event)` method
4. Update `deploy()` to call `CaseHubRuntime.startCase()` and store `engineCaseId`

The drift observer needs to:
- Filter for `NODE_DRIFTED` event type
- Deserialize `NodeDriftedData`
- Correlate `tenancyId` to application
- Build `DriftReport`
- Update consecutive drift tracker
- Signal the application case

This is the most complex single change. Implementation details will be determined during
TDD — write the tests first (observer receives CloudEvent, signals case), then implement.

- [ ] **Run all app tests**

```bash
mvn --batch-mode -o test -pl app
```

#### Step 7.3: Commit

```bash
git add app/
git commit -m "feat(#36): add drift signal bridge — NODE_DRIFTED CloudEvent → case signal

ApplicationLifecycleService observes NODE_DRIFTED CloudEvents via @ObservesAsync,
builds DriftReport with field-level diffs, tracks consecutive drift count, and
signals the application case to trigger drift-remediation child case binding."
```

---

### Task 8: DriftConvergenceHandler

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/service/DriftConvergenceHandler.java`
- Create: `app/src/test/java/io/casehub/ops/app/service/DriftConvergenceHandlerTest.java`

**Interfaces:**
- Consumes: `DesiredStateEventTypes.NODE_RECOVERED`, `NodeRecoveredData`,
  `CaseHubRuntime.signal()` for convergence signaling
- Produces: Convergence detection — signals `remediationStatus = "converged"` to child cases

#### Step 8.1: Write failing tests

- [ ] **Write tests**

```java
package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class DriftConvergenceHandlerTest {

    private DriftConvergenceHandler handler;
    private RecordingSignaler signaler;

    @BeforeEach
    void setUp() {
        signaler = new RecordingSignaler();
        handler = new DriftConvergenceHandler(signaler::signal);
    }

    @Test
    void singleNodeRecoverySignalsConvergence() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signaler.signals).hasSize(1);
        assertThat(signaler.signals.get(0).caseId).isEqualTo(caseId);
    }

    @Test
    void multiNodeRequiresAllRecovered() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1", "node-2"));

        handler.onCloudEvent(recoveredEvent("node-1"));
        assertThat(signaler.signals).isEmpty();

        handler.onCloudEvent(recoveredEvent("node-2"));
        assertThat(signaler.signals).hasSize(1);
    }

    @Test
    void untrackedNodeIgnored() {
        handler.onCloudEvent(recoveredEvent("unknown-node"));
        assertThat(signaler.signals).isEmpty();
    }

    @Test
    void nonRecoveredEventIgnored() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        var event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("/test"))
                .withType(DesiredStateEventTypes.NODE_FAULTED)
                .withData("application/json", "{}".getBytes())
                .build();
        handler.onCloudEvent(event);

        assertThat(signaler.signals).isEmpty();
    }

    @Test
    void multipleCasesTrackedIndependently() {
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        handler.registerDriftCase(case1, Set.of("node-1"));
        handler.registerDriftCase(case2, Set.of("node-2"));

        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(signaler.signals).hasSize(1);
        assertThat(signaler.signals.get(0).caseId).isEqualTo(case1);
        assertThat(handler.isTracking(case2)).isTrue();
    }

    @Test
    void convergenceDeregistersCase() {
        UUID caseId = UUID.randomUUID();
        handler.registerDriftCase(caseId, Set.of("node-1"));

        handler.onCloudEvent(recoveredEvent("node-1"));

        assertThat(handler.isTracking(caseId)).isFalse();
    }

    private CloudEvent recoveredEvent(String nodeId) {
        try {
            var data = new NodeRecoveredData("tenant:app:cluster", nodeId, "K8S_DEPLOYMENT", 1, null);
            var mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
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

    static class RecordingSignaler {
        final CopyOnWriteArrayList<SignalRecord> signals = new CopyOnWriteArrayList<>();

        void signal(UUID caseId, String path, Object value) {
            signals.add(new SignalRecord(caseId, path, value));
        }
    }
}
```

- [ ] **Run — expect FAIL**

```bash
mvn --batch-mode -o test -pl app -Dtest=DriftConvergenceHandlerTest
```

#### Step 8.2: Implement DriftConvergenceHandler

- [ ] **Create DriftConvergenceHandler.java**

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
public class DriftConvergenceHandler {

    private static final Logger LOG = Logger.getLogger(DriftConvergenceHandler.class.getName());

    private final ConcurrentHashMap<UUID, Set<String>> pendingNodes = new ConcurrentHashMap<>();
    private final ConvergenceSignaler signaler;

    @Inject
    ObjectMapper objectMapper;

    @FunctionalInterface
    public interface ConvergenceSignaler {
        void signal(UUID caseId, String path, Object value);
    }

    @Inject
    public DriftConvergenceHandler(io.casehub.api.engine.CaseHubRuntime runtime) {
        this.signaler = (caseId, path, value) ->
                runtime.signal(caseId, path, value);
    }

    DriftConvergenceHandler(ConvergenceSignaler signaler) {
        this.signaler = signaler;
    }

    public void registerDriftCase(UUID childCaseId, Set<String> driftedNodeIds) {
        pendingNodes.put(childCaseId, ConcurrentHashMap.newKeySet());
        pendingNodes.get(childCaseId).addAll(driftedNodeIds);
        LOG.fine(() -> "Tracking drift convergence for case " + childCaseId + " with " + driftedNodeIds.size() + " nodes");
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        if (!DesiredStateEventTypes.NODE_RECOVERED.equals(event.getType())) return;
        if (event.getData() == null) return;

        NodeRecoveredData data;
        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper : defaultMapper();
            data = mapper.readValue(event.getData().toBytes(), NodeRecoveredData.class);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize NodeRecoveredData", e);
            return;
        }

        String recoveredNodeId = data.nodeId();

        for (var entry : pendingNodes.entrySet()) {
            UUID caseId = entry.getKey();
            Set<String> pending = entry.getValue();
            if (pending.remove(recoveredNodeId) && pending.isEmpty()) {
                pendingNodes.remove(caseId);
                try {
                    signaler.signal(caseId, "remediationStatus", Map.of("remediationStatus", "converged"));
                    LOG.info("Drift case " + caseId + " converged — all nodes recovered");
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to signal convergence for case " + caseId, e);
                }
            }
        }
    }

    public boolean isTracking(UUID caseId) {
        return pendingNodes.containsKey(caseId);
    }

    private static ObjectMapper defaultMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }
}
```

- [ ] **Run tests — expect PASS**

```bash
mvn --batch-mode -o test -pl app -Dtest=DriftConvergenceHandlerTest
```

#### Step 8.3: Run full test suite

```bash
mvn --batch-mode -o test -pl app
```

#### Step 8.4: Commit

```bash
git add app/
git commit -m "feat(#36): add DriftConvergenceHandler — NODE_RECOVERED → case convergence

Observes NODE_RECOVERED CloudEvents. Tracks per-case pending node sets.
When all nodes for a drift case recover, signals remediationStatus=converged
to complete the child case. Multi-case correlation via ConcurrentHashMap."
```

---

### Task 9: File deferred tracking issue

**Files:**
- No code changes

- [ ] **Create tracking issue for credential rotation**

```bash
gh issue create --repo casehubio/casehub-ops --title "feat: credential rotation and expiration handling in K8sClientRegistry" --body "K8sClientRegistry caches KubernetesClient instances with no TTL. When credentials have an expires-at, the cached client continues using expired credentials until restart.

Deferred from #44 (credential resolver wiring).

Needed:
- Lazy re-resolve on authentication failure (catch 401, re-resolve, rebuild client)
- Periodic refresh for credentials with expires-at
- Client eviction and rebuild on credential change

Parent: #29"
```

---

## Verification

After all tasks complete:

```bash
mvn --batch-mode -o test -pl app
mvn --batch-mode install
```

All tests must pass. Run `ide_diagnostics` on modified files to verify no compilation errors.
