# Ops Console Phase 2: Kubernetes Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #29 — Epic: Service lifecycle management
**Issue group:** #29

**Goal:** Replace the four `@DefaultBean` SPI stubs with real Kubernetes-backed implementations using fabric8, plus startup recovery, async deployment tracking, and decommission lifecycle management.

**Architecture:** K8sResourceHandler per resource type (5 handlers), K8sClientRegistry for multi-cluster, 4 SPI implementations replacing stubs. DeploymentOutcomeTracker and DecommissionCompletionHandler observe CDI CloudEvents for async lifecycle. StartupRecoveryService restarts loops on boot. See design spec: `docs/superpowers/specs/2026-07-06-ops-console-phase2-kubernetes-design.md`.

**Tech Stack:** fabric8 kubernetes-client 7.5.2, fabric8 kubernetes-server-mock (test), Quarkus 3.x CDI, CloudEvents, Mutiny

## Global Constraints

- Java 21, Quarkus managed by casehub-parent BOM (`0.2-SNAPSHOT`)
- `@Blocking @ApplicationScoped` on all REST resources
- `@Transactional` on service methods only, never REST resources
- `tenancyId` as explicit parameter on all service methods
- Root package: `io.casehub.ops.app`
- No domain modules on classpath — app implements SPI quad directly
- Real SPI implementations are plain `@ApplicationScoped` (NOT `@DefaultBean`) — CDI gives them priority over the `@DefaultBean` stubs automatically
- Composite key format: `tenancyId + ":" + app.id + ":" + cluster.id`
- SSA field manager: `"casehub-ops"` (constant, all instances)
- Offline test: `mvn --batch-mode -o test -pl app`

---

## File Map

### New files

| File | Purpose |
|------|---------|
| `app/src/main/java/io/casehub/ops/app/k8s/K8sResourceHandler.java` | Handler interface |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sHandlerRegistry.java` | CDI handler lookup by spec type |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java` | clusterId → KubernetesClient |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sNamespaceHandler.java` | Namespace handler |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sDeploymentHandler.java` | Deployment handler |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sServiceHandler.java` | Service handler |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sIngressHandler.java` | Ingress handler |
| `app/src/main/java/io/casehub/ops/app/k8s/K8sConfigMapHandler.java` | ConfigMap handler |
| `app/src/main/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapter.java` | Real ActualStateAdapter |
| `app/src/main/java/io/casehub/ops/app/k8s/KubernetesNodeProvisioner.java` | Real NodeProvisioner |
| `app/src/main/java/io/casehub/ops/app/k8s/KubernetesFaultPolicy.java` | Real FaultPolicy |
| `app/src/main/java/io/casehub/ops/app/k8s/KubernetesEventSource.java` | Real EventSource (passive) |
| `app/src/main/java/io/casehub/ops/app/service/StartupRecoveryService.java` | @Observes StartupEvent |
| `app/src/main/java/io/casehub/ops/app/service/DeploymentOutcomeTracker.java` | Async deploy convergence |
| `app/src/main/java/io/casehub/ops/app/service/DecommissionCompletionHandler.java` | Decommission lifecycle |
| **Tests** | |
| `app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java` | Registry lifecycle |
| `app/src/test/java/io/casehub/ops/app/k8s/K8sNamespaceHandlerTest.java` | Namespace CRUD + drift |
| `app/src/test/java/io/casehub/ops/app/k8s/K8sDeploymentHandlerTest.java` | Deployment CRUD + drift |
| `app/src/test/java/io/casehub/ops/app/k8s/K8sServiceHandlerTest.java` | Service CRUD + drift |
| `app/src/test/java/io/casehub/ops/app/k8s/K8sIngressHandlerTest.java` | Ingress CRUD + drift |
| `app/src/test/java/io/casehub/ops/app/k8s/K8sConfigMapHandlerTest.java` | ConfigMap CRUD + drift |
| `app/src/test/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapterTest.java` | Adapter integration |
| `app/src/test/java/io/casehub/ops/app/k8s/KubernetesNodeProvisionerTest.java` | Provisioner integration |
| `app/src/test/java/io/casehub/ops/app/service/ApplicationLifecycleServiceTest.java` | Modified — add loop tests |
| `app/src/test/java/io/casehub/ops/app/service/DeploymentOutcomeTrackerTest.java` | Convergence tracking |
| `app/src/test/java/io/casehub/ops/app/service/DecommissionCompletionHandlerTest.java` | Decommission lifecycle |
| `app/src/test/java/io/casehub/ops/app/service/StartupRecoveryServiceTest.java` | Startup recovery |

### Modified files

| File | Change |
|------|--------|
| `app/src/main/java/io/casehub/ops/app/model/DeploymentOutcome.java` | Add `PENDING` enum value |
| `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java` | Wire ReconciliationLoop, active loop index, start-or-update |
| `app/src/main/java/io/casehub/ops/app/service/ClusterService.java` | Add delete() rejection when loops active |

---

## Task 1: K8sResourceHandler interface + K8sClientRegistry + K8sHandlerRegistry

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sResourceHandler.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sHandlerRegistry.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java`

**Interfaces:**
- Consumes: `io.casehub.ops.api.infra.InfraNodeSpec`, `io.fabric8.kubernetes.client.KubernetesClient`, `io.fabric8.kubernetes.api.model.HasMetadata`, `io.casehub.desiredstate.api.NodeStatus`
- Produces: `K8sResourceHandler<S>` interface with `specType()`, `toResource(S)`, `readStatus(KubernetesClient, S)`, `apply(KubernetesClient, S)`, `delete(KubernetesClient, S)`; `K8sClientRegistry` with `clientFor(String)`, `register(String, String)`, `deregister(String)`; `K8sHandlerRegistry` with `handlerFor(Class<S>)`

- [ ] **Step 1: Write failing test for K8sClientRegistry**

```java
// app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java
package io.casehub.ops.app.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class K8sClientRegistryTest {

    private final K8sClientRegistry registry = new K8sClientRegistry();

    @AfterEach
    void cleanup() {
        registry.shutdown();
    }

    @Test
    void registerAndRetrieveClient() {
        registry.register("ops-prod", "https://localhost:6443");
        KubernetesClient client = registry.clientFor("ops-prod");
        assertThat(client).isNotNull();
        assertThat(client.getConfiguration().getMasterUrl())
                .contains("localhost:6443");
    }

    @Test
    void clientForUnknownClusterThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("unknown"))
                .withMessageContaining("unknown");
    }

    @Test
    void deregisterClosesClient() {
        registry.register("ops-staging", "https://localhost:6443");
        KubernetesClient client = registry.clientFor("ops-staging");
        assertThat(client).isNotNull();

        registry.deregister("ops-staging");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("ops-staging"));
    }

    @Test
    void shutdownClosesAllClients() {
        registry.register("c1", "https://localhost:6443");
        registry.register("c2", "https://localhost:6444");
        registry.shutdown();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c2"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `K8sClientRegistry` does not exist

- [ ] **Step 3: Implement K8sResourceHandler interface**

```java
// app/src/main/java/io/casehub/ops/app/k8s/K8sResourceHandler.java
package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

public interface K8sResourceHandler<S extends InfraNodeSpec> {
    Class<S> specType();
    HasMetadata toResource(S spec);
    NodeStatus readStatus(KubernetesClient client, S spec);
    void apply(KubernetesClient client, S spec);
    void delete(KubernetesClient client, S spec);
}
```

- [ ] **Step 4: Implement K8sClientRegistry**

```java
// app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java
package io.casehub.ops.app.k8s;

import java.util.concurrent.ConcurrentHashMap;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sClientRegistry {

    private final ConcurrentHashMap<String, KubernetesClient> clients = new ConcurrentHashMap<>();

    public KubernetesClient clientFor(String clusterId) {
        KubernetesClient client = clients.get(clusterId);
        if (client == null) {
            throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
        }
        return client;
    }

    public void register(String clusterId, String apiUrl) {
        Config config = new ConfigBuilder()
                .withMasterUrl(apiUrl)
                .withTrustCerts(true)
                .build();
        KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(config)
                .build();
        KubernetesClient existing = clients.putIfAbsent(clusterId, client);
        if (existing != null) {
            client.close();
        }
    }

    public void deregister(String clusterId) {
        KubernetesClient client = clients.remove(clusterId);
        if (client != null) {
            client.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(KubernetesClient::close);
        clients.clear();
    }
}
```

- [ ] **Step 5: Implement K8sHandlerRegistry**

```java
// app/src/main/java/io/casehub/ops/app/k8s/K8sHandlerRegistry.java
package io.casehub.ops.app.k8s;

import java.util.Map;
import java.util.stream.Collectors;

import io.casehub.ops.api.infra.InfraNodeSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class K8sHandlerRegistry {

    private final Map<Class<? extends InfraNodeSpec>, K8sResourceHandler<?>> handlers;

    @Inject
    public K8sHandlerRegistry(@Any Instance<K8sResourceHandler<?>> discovered) {
        this.handlers = discovered.stream()
                .collect(Collectors.toMap(K8sResourceHandler::specType, h -> h));
    }

    K8sHandlerRegistry(java.util.List<K8sResourceHandler<?>> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(K8sResourceHandler::specType, h -> h));
    }

    @SuppressWarnings("unchecked")
    public <S extends InfraNodeSpec> K8sResourceHandler<S> handlerFor(Class<S> specType) {
        K8sResourceHandler<?> handler = handlers.get(specType);
        if (handler == null) {
            throw new IllegalArgumentException("No handler for spec type: " + specType.getSimpleName());
        }
        return (K8sResourceHandler<S>) handler;
    }
}
```

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sClientRegistryTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/k8s/K8sResourceHandler.java app/src/main/java/io/casehub/ops/app/k8s/K8sClientRegistry.java app/src/main/java/io/casehub/ops/app/k8s/K8sHandlerRegistry.java app/src/test/java/io/casehub/ops/app/k8s/K8sClientRegistryTest.java
git commit -m "feat(#29): K8sResourceHandler interface, K8sClientRegistry, K8sHandlerRegistry"
```

---

## Task 2: Five K8s resource handlers

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sNamespaceHandler.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sDeploymentHandler.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sServiceHandler.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sIngressHandler.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sConfigMapHandler.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/K8sNamespaceHandlerTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/K8sDeploymentHandlerTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/K8sServiceHandlerTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/K8sIngressHandlerTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/K8sConfigMapHandlerTest.java`

**Interfaces:**
- Consumes: `K8sResourceHandler<S>` from Task 1; spec types `K8sNamespaceSpec`, `K8sDeploymentSpec`, `K8sServiceSpec`, `K8sIngressSpec`, `K8sConfigMapSpec` from `io.casehub.ops.api.infra`; fabric8 model types `Namespace`, `Deployment`, `Service`, `Ingress`, `ConfigMap`
- Produces: 5 handler beans implementing `K8sResourceHandler<S>`. Each provides `toResource(S) → HasMetadata`, `apply(KubernetesClient, S)` using SSA with field manager `"casehub-ops"`, `readStatus(KubernetesClient, S) → NodeStatus` comparing `toResource()` output against live resource on managed fields only, `delete(KubernetesClient, S)`.

- [ ] **Step 1: Write failing test for K8sNamespaceHandler**

```java
// app/src/test/java/io/casehub/ops/app/k8s/K8sNamespaceHandlerTest.java
package io.casehub.ops.app.k8s;

import java.util.Map;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sNamespaceHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sNamespaceHandler handler = new K8sNamespaceHandler();

    private K8sNamespaceSpec spec;

    @BeforeEach
    void setUp() {
        spec = new K8sNamespaceSpec("casehub", Labels.of(Map.of("managed-by", "casehub-ops")));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sNamespaceSpec.class);
    }

    @Test
    void toResourceBuildsNamespace() {
        var ns = (Namespace) handler.toResource(spec);
        assertThat(ns.getMetadata().getName()).isEqualTo("casehub");
        assertThat(ns.getMetadata().getLabels()).containsEntry("managed-by", "casehub-ops");
    }

    @Test
    void applyCreatesNamespace() {
        handler.apply(client, spec);
        Namespace ns = client.namespaces().withName("casehub").get();
        assertThat(ns).isNotNull();
        assertThat(ns.getMetadata().getLabels()).containsEntry("managed-by", "casehub-ops");
    }

    @Test
    void readStatusAbsentWhenNotExists() {
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void readStatusPresentWhenExists() {
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void readStatusDriftedWhenLabelsChanged() {
        handler.apply(client, spec);
        Namespace ns = client.namespaces().withName("casehub").get();
        ns.getMetadata().getLabels().put("managed-by", "someone-else");
        client.namespaces().resource(ns).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesNamespace() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.namespaces().withName("casehub").get()).isNull();
    }

    @Test
    void applyIsIdempotent() {
        handler.apply(client, spec);
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sNamespaceHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `K8sNamespaceHandler` does not exist

- [ ] **Step 3: Implement K8sNamespaceHandler**

```java
// app/src/main/java/io/casehub/ops/app/k8s/K8sNamespaceHandler.java
package io.casehub.ops.app.k8s;

import java.util.Map;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sNamespaceHandler implements K8sResourceHandler<K8sNamespaceSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sNamespaceSpec> specType() {
        return K8sNamespaceSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sNamespaceSpec spec) {
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(spec.name())
                    .withLabels(spec.labels().entries())
                .endMetadata()
                .build();
    }

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sNamespaceSpec spec) {
        try {
            Namespace actual = client.namespaces().withName(spec.name()).get();
            if (actual == null) {
                return NodeStatus.ABSENT;
            }
            Namespace desired = (Namespace) toResource(spec);
            return labelsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sNamespaceSpec spec) {
        Namespace resource = (Namespace) toResource(spec);
        client.resource(resource)
                .fieldManager(FIELD_MANAGER)
                .forceConflicts()
                .serverSideApply();
    }

    @Override
    public void delete(KubernetesClient client, K8sNamespaceSpec spec) {
        client.namespaces().withName(spec.name()).delete();
    }

    private boolean labelsMatch(Namespace actual, Namespace desired) {
        Map<String, String> actualLabels = actual.getMetadata().getLabels();
        Map<String, String> desiredLabels = desired.getMetadata().getLabels();
        if (actualLabels == null) return desiredLabels == null || desiredLabels.isEmpty();
        return desiredLabels != null && desiredLabels.entrySet().stream()
                .allMatch(e -> e.getValue().equals(actualLabels.get(e.getKey())));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sNamespaceHandlerTest`
Expected: PASS

Note: If `serverSideApply()` is not supported by the CRUD mock server, fall back to `createOr(NonDeletingOperation::update)` in the implementation and adjust tests accordingly. The mock server's CRUD mode supports basic create/update/get/delete but may not support SSA. In that case, use `client.resource(resource).createOr(io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update)` as the primary path and note SSA as the production-preferred approach.

- [ ] **Step 5: Write failing test for K8sDeploymentHandler**

```java
// app/src/test/java/io/casehub/ops/app/k8s/K8sDeploymentHandlerTest.java
package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.types.HealthCheckSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.PortMapping;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sDeploymentHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sDeploymentHandler handler = new K8sDeploymentHandler();

    private K8sDeploymentSpec spec;

    @BeforeEach
    void setUp() {
        client.resource(new NamespaceBuilder()
                .withNewMetadata().withName("casehub").endMetadata().build())
                .create();

        spec = new K8sDeploymentSpec(
                "casehub", "inventory", "quay.io/app:1.0", 2,
                new ResourceRequirements("500m", "1Gi", "250m", "512Mi"),
                Labels.of(Map.of("app", "inventory", "managed-by", "casehub-ops")),
                List.of(new PortMapping(8080, 80, "TCP")),
                Map.of("JAVA_OPTS", "-Xmx512m"),
                Optional.of(new HealthCheckSpec("/q/health", 8080, 5, 10)));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sDeploymentSpec.class);
    }

    @Test
    void toResourceBuildsDeployment() {
        var dep = (Deployment) handler.toResource(spec);
        var container = dep.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(dep.getMetadata().getName()).isEqualTo("inventory");
        assertThat(dep.getMetadata().getNamespace()).isEqualTo("casehub");
        assertThat(dep.getSpec().getReplicas()).isEqualTo(2);
        assertThat(container.getImage()).isEqualTo("quay.io/app:1.0");
        assertThat(container.getPorts()).hasSize(1);
        assertThat(container.getEnv()).anyMatch(e ->
                e.getName().equals("JAVA_OPTS") && e.getValue().equals("-Xmx512m"));
        assertThat(container.getLivenessProbe()).isNotNull();
        assertThat(container.getLivenessProbe().getHttpGet().getPath()).isEqualTo("/q/health");
    }

    @Test
    void applyCreatesDeployment() {
        handler.apply(client, spec);
        Deployment dep = client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get();
        assertThat(dep).isNotNull();
        assertThat(dep.getSpec().getReplicas()).isEqualTo(2);
    }

    @Test
    void readStatusAbsent() {
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void readStatusPresent() {
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void readStatusDriftedWhenImageChanged() {
        handler.apply(client, spec);
        Deployment dep = client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get();
        dep.getSpec().getTemplate().getSpec().getContainers().get(0)
                .setImage("quay.io/app:2.0");
        client.apps().deployments().inNamespace("casehub").resource(dep).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void readStatusDriftedWhenReplicasChanged() {
        handler.apply(client, spec);
        Deployment dep = client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get();
        dep.getSpec().setReplicas(5);
        client.apps().deployments().inNamespace("casehub").resource(dep).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesDeployment() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get()).isNull();
    }
}
```

- [ ] **Step 6: Implement K8sDeploymentHandler**

```java
// app/src/main/java/io/casehub/ops/app/k8s/K8sDeploymentHandler.java
package io.casehub.ops.app.k8s;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sDeploymentHandler implements K8sResourceHandler<K8sDeploymentSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sDeploymentSpec> specType() {
        return K8sDeploymentSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sDeploymentSpec spec) {
        var containerBuilder = new ContainerBuilder()
                .withName(spec.name())
                .withImage(spec.image())
                .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("cpu", new Quantity(spec.resources().cpuRequest()))
                        .addToRequests("memory", new Quantity(spec.resources().memoryRequest()))
                        .addToLimits("cpu", new Quantity(spec.resources().cpuLimit()))
                        .addToLimits("memory", new Quantity(spec.resources().memoryLimit()))
                        .build())
                .withPorts(spec.ports().stream()
                        .map(p -> new ContainerPortBuilder()
                                .withContainerPort(p.containerPort())
                                .withProtocol(p.protocol())
                                .build())
                        .toList())
                .withEnv(spec.env().entrySet().stream()
                        .map(e -> new EnvVarBuilder()
                                .withName(e.getKey())
                                .withValue(e.getValue())
                                .build())
                        .toList());

        spec.healthCheck().ifPresent(hc -> {
            Probe probe = new ProbeBuilder()
                    .withNewHttpGet()
                        .withPath(hc.path())
                        .withPort(new IntOrString(hc.port()))
                    .endHttpGet()
                    .withInitialDelaySeconds(hc.initialDelaySeconds())
                    .withPeriodSeconds(hc.periodSeconds())
                    .build();
            containerBuilder.withLivenessProbe(probe);
            containerBuilder.withReadinessProbe(probe);
        });

        return new DeploymentBuilder()
                .withNewMetadata()
                    .withName(spec.name())
                    .withNamespace(spec.namespace())
                    .withLabels(spec.labels().entries())
                .endMetadata()
                .withNewSpec()
                    .withReplicas(spec.replicas())
                    .withNewSelector()
                        .withMatchLabels(Map.of("app", spec.name()))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(Map.of("app", spec.name()))
                        .endMetadata()
                        .withNewSpec()
                            .withContainers(containerBuilder.build())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sDeploymentSpec spec) {
        try {
            Deployment actual = client.apps().deployments()
                    .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) return NodeStatus.ABSENT;

            Deployment desired = (Deployment) toResource(spec);
            return managedFieldsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sDeploymentSpec spec) {
        Deployment resource = (Deployment) toResource(spec);
        client.resource(resource)
                .fieldManager(FIELD_MANAGER)
                .forceConflicts()
                .serverSideApply();
    }

    @Override
    public void delete(KubernetesClient client, K8sDeploymentSpec spec) {
        client.apps().deployments()
                .inNamespace(spec.namespace()).withName(spec.name()).delete();
    }

    private boolean managedFieldsMatch(Deployment actual, Deployment desired) {
        var actualContainer = actual.getSpec().getTemplate().getSpec().getContainers().get(0);
        var desiredContainer = desired.getSpec().getTemplate().getSpec().getContainers().get(0);

        if (!Objects.equals(actual.getSpec().getReplicas(), desired.getSpec().getReplicas())) return false;
        if (!Objects.equals(actualContainer.getImage(), desiredContainer.getImage())) return false;

        Map<String, String> actualEnv = actualContainer.getEnv() == null
                ? Map.of()
                : actualContainer.getEnv().stream()
                    .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        Map<String, String> desiredEnv = desiredContainer.getEnv() == null
                ? Map.of()
                : desiredContainer.getEnv().stream()
                    .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        if (!actualEnv.equals(desiredEnv)) return false;

        return true;
    }
}
```

- [ ] **Step 7: Run tests**

Run: `mvn --batch-mode -o test -pl app -Dtest="K8sNamespaceHandlerTest,K8sDeploymentHandlerTest"`
Expected: PASS

- [ ] **Step 8: Implement K8sServiceHandler, K8sIngressHandler, K8sConfigMapHandler with tests**

Each follows the same pattern as K8sNamespaceHandler. Key mappings:

**K8sServiceHandler** — maps `K8sServiceSpec(namespace, name, port, targetPort, serviceType, labels)` to fabric8 `Service`. Drift: compare port, targetPort, type, selector labels.

**K8sIngressHandler** — maps `K8sIngressSpec(namespace, name, host, rules, labels)` to fabric8 `Ingress`. Drift: compare host, rules.

**K8sConfigMapHandler** — maps `K8sConfigMapSpec(namespace, name, data, labels)` to fabric8 `ConfigMap`. Drift: compare data entries.

Each test follows the same structure: `@EnableKubernetesMockClient(crud = true)`, test specType, toResource, apply, readStatus (ABSENT/PRESENT/DRIFTED), delete, idempotent apply. See K8sNamespaceHandlerTest for the pattern.

- [ ] **Step 9: Run all handler tests**

Run: `mvn --batch-mode -o test -pl app -Dtest="K8sNamespaceHandlerTest,K8sDeploymentHandlerTest,K8sServiceHandlerTest,K8sIngressHandlerTest,K8sConfigMapHandlerTest"`
Expected: all PASS

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/k8s/K8sNamespaceHandler.java app/src/main/java/io/casehub/ops/app/k8s/K8sDeploymentHandler.java app/src/main/java/io/casehub/ops/app/k8s/K8sServiceHandler.java app/src/main/java/io/casehub/ops/app/k8s/K8sIngressHandler.java app/src/main/java/io/casehub/ops/app/k8s/K8sConfigMapHandler.java app/src/test/java/io/casehub/ops/app/k8s/
git commit -m "feat(#29): K8s resource handlers — Namespace, Deployment, Service, Ingress, ConfigMap"
```

---

## Task 3: Four SPI implementations

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapter.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/KubernetesNodeProvisioner.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/KubernetesFaultPolicy.java`
- Create: `app/src/main/java/io/casehub/ops/app/k8s/KubernetesEventSource.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapterTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/k8s/KubernetesNodeProvisionerTest.java`

**Interfaces:**
- Consumes: `K8sHandlerRegistry` and `K8sClientRegistry` from Task 1; handlers from Task 2; `ActualStateAdapter`, `NodeProvisioner`, `FaultPolicy`, `EventSource` from `io.casehub.desiredstate.api`; `InfraDesiredNodeSpec` from `io.casehub.ops.api.infra`; `ApplicationNodeTypes` from `io.casehub.ops.app.goal`
- Produces: `KubernetesActualStateAdapter` implementing `ActualStateAdapter`, `KubernetesNodeProvisioner` implementing `NodeProvisioner` (handledTypes: 5 K8s types, resyncInterval: 5 min), `KubernetesFaultPolicy` implementing `FaultPolicy` (returns empty), `KubernetesEventSource` implementing `EventSource` (passive, with emit/emitDrift)

- [ ] **Step 1: Write failing test for KubernetesActualStateAdapter**

```java
// app/src/test/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapterTest.java
package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesActualStateAdapterTest {

    @Test
    void readActualDelegatesToHandlerPerNode() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = new DesiredStateGraph(Map.of(nodeId, node), List.of());

        var handler = mock(K8sResourceHandler.class);
        when(handler.specType()).thenReturn((Class) K8sNamespaceSpec.class);
        when(handler.readStatus(any(), eq(nsSpec))).thenReturn(NodeStatus.PRESENT);

        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var adapter = new KubernetesActualStateAdapter(handlerRegistry, clientRegistry);
        var actual = adapter.readActual(graph, "default");

        assertThat(actual.statusOf(nodeId)).contains(NodeStatus.PRESENT);
        verify(handler).readStatus(any(), eq(nsSpec));
    }

    @Test
    void returnsUnknownWhenClusterNotRegistered() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:unknown-cluster");
        var nodeId = NodeId.of("unknown-cluster:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = new DesiredStateGraph(Map.of(nodeId, node), List.of());

        var handler = mock(K8sResourceHandler.class);
        when(handler.specType()).thenReturn((Class) K8sNamespaceSpec.class);

        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();

        var adapter = new KubernetesActualStateAdapter(handlerRegistry, clientRegistry);
        var actual = adapter.readActual(graph, "default");

        assertThat(actual.statusOf(nodeId)).contains(NodeStatus.UNKNOWN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=KubernetesActualStateAdapterTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `KubernetesActualStateAdapter` does not exist

- [ ] **Step 3: Implement KubernetesActualStateAdapter**

```java
// app/src/main/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapter.java
package io.casehub.ops.app.k8s;

import java.util.HashMap;
import java.util.Map;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KubernetesActualStateAdapter implements ActualStateAdapter {

    private final K8sHandlerRegistry handlerRegistry;
    private final K8sClientRegistry clientRegistry;

    @Inject
    public KubernetesActualStateAdapter(K8sHandlerRegistry handlerRegistry,
                                         K8sClientRegistry clientRegistry) {
        this.handlerRegistry = handlerRegistry;
        this.clientRegistry = clientRegistry;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (DesiredNode node : desired.nodes().values()) {
            statuses.put(node.id(), readNodeStatus(node));
        }
        return new ActualState(statuses);
    }

    @SuppressWarnings("unchecked")
    private NodeStatus readNodeStatus(DesiredNode node) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return NodeStatus.UNKNOWN;
        }
        String clusterId = extractClusterId(wrapper.backendId());
        try {
            var client = clientRegistry.clientFor(clusterId);
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            return handler.readStatus(client, resourceSpec);
        } catch (IllegalArgumentException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    static String extractClusterId(String backendId) {
        int colonIndex = backendId.indexOf(':');
        return colonIndex >= 0 ? backendId.substring(colonIndex + 1) : backendId;
    }
}
```

- [ ] **Step 4: Run adapter test**

Run: `mvn --batch-mode -o test -pl app -Dtest=KubernetesActualStateAdapterTest`
Expected: PASS

- [ ] **Step 5: Write failing test for KubernetesNodeProvisioner**

```java
// app/src/test/java/io/casehub/ops/app/k8s/KubernetesNodeProvisionerTest.java
package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesNodeProvisionerTest {

    @Test
    void provisionDelegatesToHandler() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = new DesiredStateGraph(Map.of(nodeId, node), List.of());

        var handler = mock(K8sResourceHandler.class);
        when(handler.specType()).thenReturn((Class) K8sNamespaceSpec.class);

        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
        var context = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        verify(handler).apply(any(), eq(nsSpec));
    }

    @Test
    void deprovisionDelegatesToHandler() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = new DesiredStateGraph(Map.of(nodeId, node), List.of());

        var handler = mock(K8sResourceHandler.class);
        when(handler.specType()).thenReturn((Class) K8sNamespaceSpec.class);

        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
        var context = new DeprovisionContext("default", graph);

        DeprovisionResult result = provisioner.deprovision(node, context);
        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        verify(handler).delete(any(), eq(nsSpec));
    }

    @Test
    void provisionFailsWhenHandlerThrows() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = new DesiredStateGraph(Map.of(nodeId, node), List.of());

        var handler = mock(K8sResourceHandler.class);
        when(handler.specType()).thenReturn((Class) K8sNamespaceSpec.class);
        doThrow(new RuntimeException("cluster down")).when(handler).apply(any(), any());

        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
        var context = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void handledTypesReturnsFiveK8sTypes() {
        var handlerRegistry = mock(K8sHandlerRegistry.class);
        var clientRegistry = new K8sClientRegistry();
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
        assertThat(provisioner.handledTypes()).hasSize(5);
    }
}
```

- [ ] **Step 6: Implement KubernetesNodeProvisioner**

```java
// app/src/main/java/io/casehub/ops/app/k8s/KubernetesNodeProvisioner.java
package io.casehub.ops.app.k8s;

import java.time.Duration;
import java.util.Set;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class KubernetesNodeProvisioner implements NodeProvisioner {

    private final K8sHandlerRegistry handlerRegistry;
    private final K8sClientRegistry clientRegistry;

    @Inject
    public KubernetesNodeProvisioner(K8sHandlerRegistry handlerRegistry,
                                      K8sClientRegistry clientRegistry) {
        this.handlerRegistry = handlerRegistry;
        this.clientRegistry = clientRegistry;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
                ApplicationNodeTypes.K8S_NAMESPACE,
                ApplicationNodeTypes.K8S_DEPLOYMENT,
                ApplicationNodeTypes.K8S_SERVICE,
                ApplicationNodeTypes.K8S_INGRESS,
                ApplicationNodeTypes.K8S_CONFIGMAP);
    }

    @Override
    public Duration resyncInterval() {
        return Duration.ofMinutes(5);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ProvisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }
        try {
            String clusterId = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
            var client = clientRegistry.clientFor(clusterId);
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            handler.apply(client, resourceSpec);
            return new ProvisionResult.Success();
        } catch (Exception e) {
            return new ProvisionResult.Failed(e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new DeprovisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }
        try {
            String clusterId = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
            var client = clientRegistry.clientFor(clusterId);
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            handler.delete(client, resourceSpec);
            return new DeprovisionResult.Success();
        } catch (Exception e) {
            return new DeprovisionResult.Failed(e.getMessage());
        }
    }
}
```

- [ ] **Step 7: Implement KubernetesFaultPolicy**

```java
// app/src/main/java/io/casehub/ops/app/k8s/KubernetesFaultPolicy.java
package io.casehub.ops.app.k8s;

import java.util.List;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KubernetesFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        return List.of();
    }
}
```

- [ ] **Step 8: Implement KubernetesEventSource**

```java
// app/src/main/java/io/casehub/ops/app/k8s/KubernetesEventSource.java
package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KubernetesEventSource implements EventSource {

    private final Multi<StateEvent> stream;
    private volatile MultiEmitter<? super StateEvent> emitter;

    public KubernetesEventSource() {
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

    public void emitDrift(NodeId nodeId) {
        emit(new StateEvent(nodeId, NodeStatus.DRIFTED, "drift detected"));
    }
}
```

- [ ] **Step 9: Run all SPI tests**

Run: `mvn --batch-mode -o test -pl app -Dtest="KubernetesActualStateAdapterTest,KubernetesNodeProvisionerTest"`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapter.java app/src/main/java/io/casehub/ops/app/k8s/KubernetesNodeProvisioner.java app/src/main/java/io/casehub/ops/app/k8s/KubernetesFaultPolicy.java app/src/main/java/io/casehub/ops/app/k8s/KubernetesEventSource.java app/src/test/java/io/casehub/ops/app/k8s/KubernetesActualStateAdapterTest.java app/src/test/java/io/casehub/ops/app/k8s/KubernetesNodeProvisionerTest.java
git commit -m "feat(#29): KubernetesActualStateAdapter, KubernetesNodeProvisioner, KubernetesFaultPolicy, KubernetesEventSource"
```

---

## Task 4: DeploymentOutcome.PENDING + ApplicationLifecycleService rewiring

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/model/DeploymentOutcome.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java`
- Modify: `app/src/test/java/io/casehub/ops/app/service/ApplicationLifecycleServiceTest.java`

**Interfaces:**
- Consumes: `ReconciliationLoop` from `io.casehub.desiredstate.runtime`, `ApplicationGoalCompiler`, `DesiredStateGraphFactory`, `ClusterService`, `K8sClientRegistry` from Tasks 1-3
- Produces: `DeploymentOutcome.PENDING` enum value; `ApplicationLifecycleService.deploy()` now calls `ReconciliationLoop.start()`/`updateDesired()` with composite key `tenancyId:appId:clusterId`; active loop index via `trackLoopKey(String clusterId, String compositeKey)`, `removeLoopKey(String compositeKey)`, `hasActiveLoopsForCluster(String clusterId)`, `activeLoopKeysForApp(UUID appId)`

- [ ] **Step 1: Add PENDING to DeploymentOutcome**

Modify `app/src/main/java/io/casehub/ops/app/model/DeploymentOutcome.java`:

```java
package io.casehub.ops.app.model;

public enum DeploymentOutcome {
    PENDING, SUCCESS, PARTIAL, FAILED, PENDING_APPROVAL
}
```

- [ ] **Step 2: Write failing test for deploy() with ReconciliationLoop**

Add to `ApplicationLifecycleServiceTest.java`:

```java
@Test
@Transactional
void deployStartsReconciliationLoops() {
    var app = lifecycleService.createDraft("deploy-test", "Test", "[]", "default");

    var cluster = new ClusterReferenceEntity();
    cluster.name = "test-cluster";
    cluster.apiUrl = "https://localhost:6443";
    cluster.namespace = "default";
    cluster.clusterType = ClusterType.KUBERNETES;
    clusterService.register(cluster, "default");

    lifecycleService.deploy(app.id, "default");

    assertThat(app.status).isEqualTo(ApplicationStatus.DEPLOYING);
    assertThat(lifecycleService.hasActiveLoopsForCluster(cluster.id.toString())).isTrue();
}

@Test
@Transactional
void deployStartOrUpdateHandlesExistingLoop() {
    var app = lifecycleService.createDraft("idempotent-test", "Test", "[]", "default");

    var cluster = new ClusterReferenceEntity();
    cluster.name = "idempotent-cluster";
    cluster.apiUrl = "https://localhost:6443";
    cluster.namespace = "default";
    cluster.clusterType = ClusterType.KUBERNETES;
    clusterService.register(cluster, "default");

    lifecycleService.deploy(app.id, "default");
    assertThatNoException().isThrownBy(() -> lifecycleService.deploy(app.id, "default"));
}
```

- [ ] **Step 3: Rewire ApplicationLifecycleService**

Inject `ReconciliationLoop` and `K8sClientRegistry`. Add active loop index. Replace TODO comments with real calls. Use start-or-update semantics. Record `PENDING` outcome. See the full implementation in the design spec §ApplicationLifecycleService Changes.

Key changes:
- `@Inject ReconciliationLoop reconciliationLoop`
- `@Inject K8sClientRegistry clientRegistry`
- Active loop index: `ConcurrentHashMap<String, Set<String>>` mapping clusterId → compositeKeys
- `deploy()`: register cluster in clientRegistry, compile goal, `start()`/catch/`updateDesired()`, `trackLoopKey()`, record PENDING
- `decommission()`: `updateDesired(key, emptyGraph)` per cluster, collect keys
- `trackLoopKey()`, `removeLoopKey()`, `hasActiveLoopsForCluster()`, `activeLoopKeysForApp()`

- [ ] **Step 4: Run tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=ApplicationLifecycleServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/model/DeploymentOutcome.java app/src/main/java/io/casehub/ops/app/service/ApplicationLifecycleService.java app/src/test/java/io/casehub/ops/app/service/ApplicationLifecycleServiceTest.java
git commit -m "feat(#29): wire ReconciliationLoop in ApplicationLifecycleService, active loop index, PENDING outcome"
```

---

## Task 5: DeploymentOutcomeTracker + DecommissionCompletionHandler

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/service/DeploymentOutcomeTracker.java`
- Create: `app/src/main/java/io/casehub/ops/app/service/DecommissionCompletionHandler.java`
- Test: `app/src/test/java/io/casehub/ops/app/service/DeploymentOutcomeTrackerTest.java`
- Test: `app/src/test/java/io/casehub/ops/app/service/DecommissionCompletionHandlerTest.java`

**Interfaces:**
- Consumes: `CloudEvent` via `@ObservesAsync`, `ReconciliationCompletedData` (deserialized from CloudEvent data), `ReconciliationLoop.stop()`, `ApplicationLifecycleService.removeLoopKey()`, `DeploymentRecordEntity`, `ApplicationEntity`
- Produces: `DeploymentOutcomeTracker` with `registerDeployment(UUID deploymentId, Set<String> clusterIds)`, `onCloudEvent(@ObservesAsync CloudEvent)`, `@Scheduled checkTimeout()`; `DecommissionCompletionHandler` with `registerDecommission(UUID appId, Set<String> compositeKeys)`, `cancelDecommission(UUID appId)`, `onCloudEvent(@ObservesAsync CloudEvent)`, `@Scheduled checkTimeout()`

- [ ] **Step 1: Write failing test for DeploymentOutcomeTracker**

```java
// app/src/test/java/io/casehub/ops/app/service/DeploymentOutcomeTrackerTest.java
package io.casehub.ops.app.service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

class DeploymentOutcomeTrackerTest {

    private DeploymentOutcomeTracker tracker;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        tracker = new DeploymentOutcomeTracker();
    }

    @Test
    void registersAndTracksDeployment() {
        var deploymentId = UUID.randomUUID();
        tracker.registerDeployment(deploymentId, Set.of("c1", "c2"));
        assertThat(tracker.isTracking(deploymentId)).isTrue();
    }

    @Test
    void marksClusterConvergedOnCleanReconciliation() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", key);

        var event = buildCompletedEvent(key, 0, 0);
        tracker.onCloudEvent(event);

        assertThat(tracker.isConverged(deploymentId, "c1")).isTrue();
    }

    @Test
    void doesNotConvergeOnFaults() {
        var deploymentId = UUID.randomUUID();
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        tracker.registerDeployment(deploymentId, Set.of("c1"));
        tracker.associateKey(deploymentId, "c1", key);

        var event = buildCompletedEvent(key, 0, 1);
        tracker.onCloudEvent(event);

        assertThat(tracker.isConverged(deploymentId, "c1")).isFalse();
    }

    private CloudEvent buildCompletedEvent(String tenancyId, int additionsCount, int faultCount) {
        var data = new ReconciliationCompletedData(
                tenancyId, 1, 3, additionsCount, 0, faultCount, Instant.now());
        try {
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("urn:io.casehub:desiredstate"))
                    .withType("io.casehub.desiredstate.reconciliation.completed")
                    .withTime(OffsetDateTime.now())
                    .withData("application/json", MAPPER.writeValueAsBytes(data))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=DeploymentOutcomeTrackerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `DeploymentOutcomeTracker` does not exist

- [ ] **Step 3: Implement DeploymentOutcomeTracker**

Core structure per design spec §DeploymentOutcomeTracker. Key methods:
- `registerDeployment(UUID, Set<String>)` — initializes per-cluster convergence map
- `associateKey(UUID, String, String)` — maps clusterId → compositeKey for event correlation
- `onCloudEvent(@ObservesAsync CloudEvent)` — filters for reconciliation.completed, checks `additionsCount == 0 && faultCount == 0`, marks cluster converged
- `@Scheduled(every = "1m") checkTimeout()` — transitions PENDING deployments past timeout to FAILED
- `isTracking()`, `isConverged()` — query methods for tests

- [ ] **Step 4: Write failing test for DecommissionCompletionHandler**

```java
// app/src/test/java/io/casehub/ops/app/service/DecommissionCompletionHandlerTest.java
package io.casehub.ops.app.service;

import java.util.Set;
import java.util.UUID;

import io.casehub.desiredstate.runtime.ReconciliationLoop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecommissionCompletionHandlerTest {

    private DecommissionCompletionHandler handler;
    private ReconciliationLoop reconciliationLoop;
    private ApplicationLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        reconciliationLoop = mock(ReconciliationLoop.class);
        lifecycleService = mock(ApplicationLifecycleService.class);
        handler = new DecommissionCompletionHandler(reconciliationLoop, lifecycleService);
    }

    @Test
    void registersDecommission() {
        var appId = UUID.randomUUID();
        handler.registerDecommission(appId, Set.of("key1", "key2"));
        assertThat(handler.isTracking(appId)).isTrue();
    }

    @Test
    void cancelDecommissionRemovesTracking() {
        var appId = UUID.randomUUID();
        handler.registerDecommission(appId, Set.of("key1"));
        handler.cancelDecommission(appId);
        assertThat(handler.isTracking(appId)).isFalse();
    }

    @Test
    void convergenceStopsLoopAndRemovesKey() {
        var appId = UUID.randomUUID();
        var key = "default:" + appId + ":c1";
        handler.registerDecommission(appId, Set.of(key));

        handler.onKeyConverged(key);

        verify(reconciliationLoop).stop(key);
        verify(lifecycleService).removeLoopKey(key);
    }
}
```

- [ ] **Step 5: Implement DecommissionCompletionHandler**

Core structure per design spec §DecommissionCompletionHandler. Key methods:
- `registerDecommission(UUID, Set<String>)` — tracks appId → compositeKeys
- `cancelDecommission(UUID)` — removes tracking (called by deploy())
- `onCloudEvent(@ObservesAsync CloudEvent)` — checks `removalsCount == 0 && faultCount == 0`, calls `onKeyConverged()`
- `onKeyConverged(String)` — stops loop, removes from index, transitions to DECOMMISSIONED when all keys converged
- `@Scheduled(every = "1m") checkTimeout()` — force-stops after timeout

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode -o test -pl app -Dtest="DeploymentOutcomeTrackerTest,DecommissionCompletionHandlerTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/service/DeploymentOutcomeTracker.java app/src/main/java/io/casehub/ops/app/service/DecommissionCompletionHandler.java app/src/test/java/io/casehub/ops/app/service/
git commit -m "feat(#29): DeploymentOutcomeTracker + DecommissionCompletionHandler — async lifecycle tracking"
```

---

## Task 6: StartupRecoveryService + ClusterService.delete() guard

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/service/StartupRecoveryService.java`
- Modify: `app/src/main/java/io/casehub/ops/app/service/ClusterService.java`
- Test: `app/src/test/java/io/casehub/ops/app/service/StartupRecoveryServiceTest.java`
- Modify: `app/src/test/java/io/casehub/ops/app/service/ClusterServiceTest.java`

**Interfaces:**
- Consumes: `ReconciliationLoop`, `ApplicationGoalCompiler`, `DesiredStateGraphFactory`, `ClusterService`, `K8sClientRegistry`, `ApplicationLifecycleService.trackLoopKey()`, `DecommissionCompletionHandler.registerDecommission()`, `ApplicationEntity`, `ClusterReferenceEntity`
- Produces: `StartupRecoveryService.onStartup(@Observes StartupEvent)` — registers clusters, restarts loops for non-terminal apps, re-registers decommission tracking; `ClusterService.delete()` returns 409 when `ApplicationLifecycleService.hasActiveLoopsForCluster()` is true

- [ ] **Step 1: Write failing test for StartupRecoveryService**

```java
// app/src/test/java/io/casehub/ops/app/service/StartupRecoveryServiceTest.java
package io.casehub.ops.app.service;

import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ClusterType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class StartupRecoveryServiceTest {

    @Inject
    StartupRecoveryService startupRecoveryService;

    @Inject
    ApplicationLifecycleService lifecycleService;

    @Test
    @Transactional
    void recoversRunningApplications() {
        var cluster = new ClusterReferenceEntity();
        cluster.name = "recovery-cluster";
        cluster.apiUrl = "https://localhost:6443";
        cluster.namespace = "default";
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = "recovery-tenant";
        cluster.persist();

        var app = new ApplicationEntity();
        app.name = "recovery-app";
        app.tenancyId = "recovery-tenant";
        app.servicesJson = "[]";
        app.status = ApplicationStatus.RUNNING;
        app.persist();

        startupRecoveryService.recover();

        assertThat(lifecycleService.hasActiveLoopsForCluster(cluster.id.toString())).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=StartupRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `StartupRecoveryService` does not exist

- [ ] **Step 3: Implement StartupRecoveryService**

Full implementation per design spec §StartupRecoveryService. The `@Observes StartupEvent` delegates to `recover()` for testability. Recovers DEPLOYING, RUNNING, DEGRADED, and DECOMMISSIONING applications. DECOMMISSIONING apps get empty graphs and re-registered decommission tracking.

- [ ] **Step 4: Write failing test for ClusterService.delete() rejection**

Add to `ClusterServiceTest.java`:

```java
@Test
@Transactional
void deleteRejectsWhenActiveLoopsExist() {
    var cluster = new ClusterReferenceEntity();
    cluster.name = "guarded-cluster";
    cluster.apiUrl = "https://localhost:6443";
    cluster.namespace = "default";
    cluster.clusterType = ClusterType.KUBERNETES;
    clusterService.register(cluster, "default");

    lifecycleService.trackLoopKey(cluster.id.toString(), "default:" + UUID.randomUUID() + ":" + cluster.id);

    assertThatThrownBy(() -> clusterService.delete(cluster.id, "default"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active reconciliation loops");
}
```

- [ ] **Step 5: Modify ClusterService.delete()**

Inject `ApplicationLifecycleService`. Before deleting, check `hasActiveLoopsForCluster(clusterId.toString())`. If true, throw `IllegalStateException`. Also deregister from `K8sClientRegistry`.

- [ ] **Step 6: Run tests**

Run: `mvn --batch-mode -o test -pl app -Dtest="StartupRecoveryServiceTest,ClusterServiceTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/service/StartupRecoveryService.java app/src/main/java/io/casehub/ops/app/service/ClusterService.java app/src/test/java/io/casehub/ops/app/service/
git commit -m "feat(#29): StartupRecoveryService + ClusterService delete guard (409 on active loops)"
```

---

## Task 7: Full build verification

**Files:**
- Potentially modify any files from Tasks 1-6 for build fixes

**Interfaces:**
- Consumes: everything from Tasks 1-6
- Produces: green build across all modules

- [ ] **Step 1: Run full project build**

Run: `mvn --batch-mode install -DskipTests`
Expected: BUILD SUCCESS across all modules

- [ ] **Step 2: Run full test suite**

Run: `mvn --batch-mode -o test -pl app`
Expected: all tests PASS (Phase 1 tests + Phase 2 tests)

- [ ] **Step 3: Run full project tests to verify no downstream breakage**

Run: `mvn --batch-mode test`
Expected: all modules PASS

- [ ] **Step 4: Fix any issues found**

Common issues:
- CDI ambiguity between `@DefaultBean` stubs and real implementations (should not happen — real impls are plain `@ApplicationScoped`, CDI prefers them over `@DefaultBean`)
- Missing CDI beans for ReconciliationLoop injection (check `quarkus.arc.selected-alternatives` in application.properties)
- FaultPolicyListProducer may need updating if CDI resolution changes with real FaultPolicy bean
- fabric8 test dependency scope issues

- [ ] **Step 5: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "fix(#29): build fixes for Phase 2 Kubernetes integration"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** Task 1 = foundation interfaces/registries. Task 2 = 5 handlers. Task 3 = 4 SPI implementations. Task 4 = ApplicationLifecycleService rewiring + PENDING. Task 5 = async lifecycle (DeploymentOutcomeTracker + DecommissionCompletionHandler). Task 6 = startup recovery + cluster guard. Task 7 = build verification. All spec requirements covered including design review findings (composite key with appId, start-or-update, PENDING outcome, active loop index, deregistration guard, decommission completion, deploy-during-decommission cancellation).
- [x] **Placeholder scan:** No TBD/TODO. Steps 8 (Task 2) and Step 3 (Tasks 5, 6) reference "per design spec" for complex implementations — this is intentional: the design spec is the authoritative source and duplicating 100+ lines of code here would create consistency risk. The task description and interfaces block provide enough context for an implementer to work from the spec.
- [x] **Type consistency:** `K8sResourceHandler<S>`, `K8sClientRegistry`, `K8sHandlerRegistry`, `KubernetesActualStateAdapter`, `KubernetesNodeProvisioner`, `KubernetesFaultPolicy`, `KubernetesEventSource`, `DeploymentOutcomeTracker`, `DecommissionCompletionHandler`, `StartupRecoveryService` — names consistent across all tasks. `extractClusterId()` is a static method on `KubernetesActualStateAdapter` used by both adapter and provisioner.

## Not in Phase 2

- Engine case creation and case lifecycle (Phase 3)
- Active K8s Watch integration (#40)
- Approval workflow (#43)
- Credential resolver (#44)
- K8s-aware FaultPolicy (#45)
- UI components and pages application (Phase 4)
- Demo environment (Phase 5)
