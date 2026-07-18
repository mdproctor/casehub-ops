# Approval Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #43 — feat: approval workflow for provisioning operations (Phase 3)
**Issue group:** #43

**Goal:** Replace the in-memory approval handler with WorkItem-backed persistence,
add K8s approval gates for high-risk operations, and implement the REST API.

**Architecture:** Delete `OpsPendingApprovalHandler` (in-memory scaffolding), activate
`WorkItemPendingApprovalHandler` from `casehub-desiredstate-work` via classpath
presence. Add `K8sApprovalEvaluator` to classify K8s operations by risk. Wire
`KubernetesNodeProvisioner` to use the approval flow. Implement `ApprovalResource`
REST endpoints using `WorkItemService.scan()` + `PlanStore`.

**Tech Stack:** Java 22, Quarkus 3.x, casehub-desiredstate-api, casehub-work,
casehub-desiredstate-work

## Global Constraints

- Pre-release project — breaking changes cost nothing
- Single-domain CDI constraint (ARC42STORIES §2) — no domain modules on app classpath
- All provisioner changes follow the `DeploymentNodeProvisioner` pattern
- `InfraDesiredNodeSpec` wraps `InfraNodeSpec` + `backendId` — K8s provisioner uses this
- `WorkItemService` is on app classpath via `casehub-work` dependency
- `TenancyFilter` sets `casehub.tenancyId` property on every request
- Changes 1+2 must be atomic (same commit) — see spec §Change 2

---

### Task 1: Activate WorkItem handler and delete in-memory handler

**Files:**
- Modify: `app/pom.xml` — add `casehub-desiredstate-work` dependency
- Delete: `api/src/main/java/io/casehub/ops/api/approval/OpsPendingApprovalHandler.java`
  (use `ide_refactor_safe_delete`)
- Delete: `api/src/test/java/io/casehub/ops/api/approval/OpsPendingApprovalHandlerTest.java`
  (use `ide_refactor_safe_delete`)

**Interfaces:**
- Consumes: `PendingApprovalHandler` SPI from `casehub-desiredstate-api`
- Produces: `WorkItemPendingApprovalHandler` as the sole `PendingApprovalHandler` CDI bean

- [ ] **Step 1: Add `casehub-desiredstate-work` dependency to `app/pom.xml`**

Add after the `casehub-desiredstate` dependency (line 48):

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate-work</artifactId>
</dependency>
```

- [ ] **Step 2: Check references to `OpsPendingApprovalHandler` before deletion**

Use `ide_find_references` on `io.casehub.ops.api.approval.OpsPendingApprovalHandler`
to confirm only test references exist (already verified: only
`ApprovalLifecycleIntegrationTest` and `OpsPendingApprovalHandlerTest`).

- [ ] **Step 3: Delete `OpsPendingApprovalHandler` and its test**

Use `ide_refactor_safe_delete` on:
- `api/src/main/java/io/casehub/ops/api/approval/OpsPendingApprovalHandler.java`
- `api/src/test/java/io/casehub/ops/api/approval/OpsPendingApprovalHandlerTest.java`

If safe delete reports usages in `ApprovalLifecycleIntegrationTest`, use `force: true`
(that test is rewritten in Task 5).

- [ ] **Step 4: Fix `ApprovalLifecycleIntegrationTest` compilation**

The test directly constructs `OpsPendingApprovalHandler`. Replace with
`MockPendingApprovalHandler` from `casehub-desiredstate-testing` (already a test
dependency). This is a temporary fix — Task 5 rewrites the test properly.

In `ApprovalLifecycleIntegrationTest.setUp()`, replace:

```java
// Old:
handler = new OpsPendingApprovalHandler(planStore);
// New:
handler = new MockPendingApprovalHandler();
```

Update the field type from `OpsPendingApprovalHandler` to `MockPendingApprovalHandler`.
Remove the `handler.approve(...)` and `handler.reject(...)` calls — use
`MockPendingApprovalHandler.approveNext()` / `MockPendingApprovalHandler.rejectNext()`
instead (check `MockPendingApprovalHandler` API first with `ide_file_structure`).

If `MockPendingApprovalHandler` doesn't support approve/reject simulation, use
the `WorkItemPendingApprovalHandler` with an `InMemoryWorkItemCreator` pattern from
`WorkItemPendingApprovalHandlerTest` in casehub-desiredstate. Read that test first:
`work-adapter/src/test/java/io/casehub/desiredstate/work/WorkItemPendingApprovalHandlerTest.java`

- [ ] **Step 5: Build and verify**

Run: `mvn --batch-mode -o test -pl app`
Expected: All existing tests pass. `WorkItemPendingApprovalHandler` is now the active
`PendingApprovalHandler` bean.

- [ ] **Step 6: Commit**

```bash
git add app/pom.xml api/src/main/java/io/casehub/ops/api/approval/OpsPendingApprovalHandler.java api/src/test/java/io/casehub/ops/api/approval/OpsPendingApprovalHandlerTest.java deployment/src/test/java/io/casehub/ops/deployment/ApprovalLifecycleIntegrationTest.java
git commit -m "feat(#43): activate WorkItem-backed PendingApprovalHandler

Replace in-memory OpsPendingApprovalHandler with WorkItemPendingApprovalHandler
from casehub-desiredstate-work. Approval state now persists via casehub-work
WorkItems instead of ConcurrentHashMap."
```

---

### Task 2: K8sApprovalEvaluator

**Files:**
- Create: `app/src/main/java/io/casehub/ops/app/k8s/K8sApprovalEvaluator.java`
- Create: `app/src/test/java/io/casehub/ops/app/k8s/K8sApprovalEvaluatorTest.java`

**Interfaces:**
- Consumes: `ApprovalEvaluator` from `io.casehub.ops.api.approval`,
  `ApplicationNodeTypes` from `io.casehub.ops.app.goal`,
  `InfraDesiredNodeSpec` / `InfraNodeSpec` subtypes from `io.casehub.ops.api.infra`
- Produces: `K8sApprovalEvaluator` — `@ApplicationScoped` CDI bean implementing
  `ApprovalEvaluator`. Methods: `evaluate(DesiredNode, StepAction, String) → ApprovalDecision`

- [ ] **Step 1: Write failing test — namespace deprovision is CRITICAL**

Create `app/src/test/java/io/casehub/ops/app/k8s/K8sApprovalEvaluatorTest.java`:

```java
package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class K8sApprovalEvaluatorTest {

    private final K8sApprovalEvaluator evaluator = new K8sApprovalEvaluator();

    @Test
    void namespaceDeprovision_isCritical_requiresApproval() {
        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod-billing", Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("ns-1"),
                ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.CRITICAL);
        assertThat(req.plan().summary()).contains("Deprovision").contains("prod-billing");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sApprovalEvaluatorTest#namespaceDeprovision_isCritical_requiresApproval`
Expected: FAIL — `K8sApprovalEvaluator` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/casehub/ops/app/k8s/K8sApprovalEvaluator.java`:

```java
package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sApprovalEvaluator implements ApprovalEvaluator {

    private static final ApprovalThresholds THRESHOLDS =
            new ApprovalThresholds(RiskClassification.HIGH);

    @Override
    public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ApprovalDecision.AutoApproved();
        }

        RiskClassification risk = classifyRisk(node.type(), action);
        if (!THRESHOLDS.requiresApproval(risk)) {
            return new ApprovalDecision.AutoApproved();
        }

        var plan = new ApprovalPlan(
                node.id(), action, risk,
                generateSummary(wrapper, action),
                tenancyId, node.spec(), null);
        return new ApprovalDecision.RequiresApproval(plan);
    }

    private RiskClassification classifyRisk(NodeType type, StepAction action) {
        if (type.equals(ApplicationNodeTypes.K8S_NAMESPACE)) {
            return action == StepAction.DEPROVISION
                    ? RiskClassification.CRITICAL : RiskClassification.LOW;
        }
        if (type.equals(ApplicationNodeTypes.K8S_DEPLOYMENT)) {
            return action == StepAction.DEPROVISION
                    ? RiskClassification.HIGH : RiskClassification.MEDIUM;
        }
        if (type.equals(ApplicationNodeTypes.K8S_SERVICE)
                || type.equals(ApplicationNodeTypes.K8S_INGRESS)
                || type.equals(ApplicationNodeTypes.K8S_CONFIGMAP)) {
            return action == StepAction.DEPROVISION
                    ? RiskClassification.MEDIUM : RiskClassification.LOW;
        }
        return RiskClassification.LOW;
    }

    private String generateSummary(InfraDesiredNodeSpec wrapper, StepAction action) {
        String verb = action == StepAction.PROVISION ? "Provision" : "Deprovision";
        String cluster = wrapper.backendId();
        return switch (wrapper.resourceSpec()) {
            case K8sNamespaceSpec s ->
                    verb + " namespace '" + s.name() + "' on " + cluster;
            case K8sDeploymentSpec s ->
                    verb + " deployment '" + s.namespace() + "/" + s.name()
                            + "' (" + s.image() + ", " + s.replicas() + " replicas) on " + cluster;
            case K8sServiceSpec s ->
                    verb + " service '" + s.namespace() + "/" + s.name()
                            + "' (port " + s.port() + ") on " + cluster;
            case K8sIngressSpec s ->
                    verb + " ingress '" + s.namespace() + "/" + s.name()
                            + "' (host: " + s.host() + ") on " + cluster;
            case K8sConfigMapSpec s ->
                    verb + " configmap '" + s.namespace() + "/" + s.name() + "' on " + cluster;
            default -> verb + " " + wrapper.resourceSpec().resourceType() + " on " + cluster;
        };
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sApprovalEvaluatorTest#namespaceDeprovision_isCritical_requiresApproval`
Expected: PASS

- [ ] **Step 5: Add remaining classification tests**

Add to `K8sApprovalEvaluatorTest`:

```java
@Test
void namespaceProvision_isLow_autoApproves() {
    var spec = new InfraDesiredNodeSpec(
            new K8sNamespaceSpec("dev-sandbox", Labels.empty()),
            "kubernetes:ops-dev");
    var node = new DesiredNode(NodeId.of("ns-2"),
            ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);

    var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

    assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
}

@Test
void deploymentDeprovision_isHigh_requiresApproval() {
    var spec = new InfraDesiredNodeSpec(
            new K8sDeploymentSpec("prod", "api-server", "myapp:1.0", 3,
                    new ResourceRequirements("256Mi", "512Mi", "250m", "500m"),
                    Labels.empty()),
            "kubernetes:ops-prod");
    var node = new DesiredNode(NodeId.of("dep-1"),
            ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

    var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

    assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
    var req = (ApprovalDecision.RequiresApproval) decision;
    assertThat(req.plan().risk()).isEqualTo(RiskClassification.HIGH);
}

@Test
void deploymentProvision_isMedium_autoApproves() {
    var spec = new InfraDesiredNodeSpec(
            new K8sDeploymentSpec("staging", "web", "app:2.0", 2,
                    new ResourceRequirements("128Mi", "256Mi", "100m", "200m"),
                    Labels.empty()),
            "kubernetes:ops-staging");
    var node = new DesiredNode(NodeId.of("dep-2"),
            ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

    var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

    assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
}

@Test
void serviceDeprovision_isMedium_autoApproves() {
    var spec = new InfraDesiredNodeSpec(
            new K8sServiceSpec("prod", "api", 80, 8080,
                    K8sServiceSpec.ServiceType.CLUSTER_IP, Labels.empty(), Labels.empty()),
            "kubernetes:ops-prod");
    var node = new DesiredNode(NodeId.of("svc-1"),
            ApplicationNodeTypes.K8S_SERVICE, spec, HumanGating.NONE);

    var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

    assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
}

@Test
void configmapDeprovision_isMedium_autoApproves() {
    var spec = new InfraDesiredNodeSpec(
            new K8sConfigMapSpec("prod", "app-config", Map.of("key", "val"), Labels.empty()),
            "kubernetes:ops-prod");
    var node = new DesiredNode(NodeId.of("cm-1"),
            ApplicationNodeTypes.K8S_CONFIGMAP, spec, HumanGating.NONE);

    var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

    assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
}

@Test
void nonInfraSpec_autoApproves() {
    var node = new DesiredNode(NodeId.of("other-1"),
            NodeType.of("unknown"), new TestNodeSpec(), HumanGating.NONE);

    var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

    assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
}

record TestNodeSpec() implements NodeSpec {}
```

- [ ] **Step 6: Run all evaluator tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=K8sApprovalEvaluatorTest`
Expected: All PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/k8s/K8sApprovalEvaluator.java app/src/test/java/io/casehub/ops/app/k8s/K8sApprovalEvaluatorTest.java
git commit -m "feat(#43): K8s approval evaluator — risk classification by NodeType × StepAction

Namespace deletion CRITICAL, deployment deprovision HIGH, threshold at HIGH.
Generates human-readable summaries with cluster and resource context."
```

---

### Task 3: KubernetesNodeProvisioner approval integration

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/k8s/KubernetesNodeProvisioner.java`
- Modify: `app/src/test/java/io/casehub/ops/app/k8s/KubernetesNodeProvisionerTest.java`

**Interfaces:**
- Consumes: `K8sApprovalEvaluator` (Task 2), `PlanStore` from
  `io.casehub.ops.api.approval`, `InfraDesiredNodeSpec` from `io.casehub.ops.api.infra`
- Produces: `KubernetesNodeProvisioner` with approval flow — returns
  `ProvisionResult.PendingApproval` for HIGH/CRITICAL ops, handles re-entry

- [ ] **Step 1: Write failing test — high-risk provision returns PendingApproval**

Add to `KubernetesNodeProvisionerTest`:

```java
@Test
void provision_highRisk_returnsPendingApproval() {
    var planStore = new InMemoryPlanStore();
    var evaluator = new K8sApprovalEvaluator();
    var handler = new K8sHandlerRegistry();
    handler.register(new StubHandler(NodeStatus.ABSENT));
    var clientRegistry = new StubK8sClientRegistry();
    var provisioner = new KubernetesNodeProvisioner(handler, clientRegistry, evaluator, planStore);

    var spec = new InfraDesiredNodeSpec(
            new K8sNamespaceSpec("prod-billing", Labels.empty()),
            "kubernetes:ops-prod");
    var node = new DesiredNode(NodeId.of("ns-1"),
            ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);
    var graph = new DefaultDesiredStateGraphFactory().empty();
    var context = new ProvisionContext("tenant-1", graph);

    var result = provisioner.deprovision(node, new DeprovisionContext("tenant-1", graph));

    assertThat(result).isInstanceOf(DeprovisionResult.PendingApproval.class);
    var pa = (DeprovisionResult.PendingApproval) result;
    assertThat(planStore.retrieve(pa.planReference())).isPresent();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=KubernetesNodeProvisionerTest#provision_highRisk_returnsPendingApproval`
Expected: FAIL — constructor doesn't accept evaluator/planStore.

- [ ] **Step 3: Add approval fields to KubernetesNodeProvisioner**

Use `ide_edit_member` to replace the class. Add `ApprovalEvaluator` and `PlanStore`
fields and update the constructor:

```java
@ApplicationScoped
public class KubernetesNodeProvisioner implements NodeProvisioner {

    private final K8sHandlerRegistry handlerRegistry;
    private final K8sClientRegistry clientRegistry;
    private final ApprovalEvaluator approvalEvaluator;
    private final PlanStore planStore;

    @Inject
    public KubernetesNodeProvisioner(K8sHandlerRegistry handlerRegistry,
                                     K8sClientRegistry clientRegistry,
                                     ApprovalEvaluator approvalEvaluator,
                                     PlanStore planStore) {
        this.handlerRegistry = handlerRegistry;
        this.clientRegistry = clientRegistry;
        this.approvalEvaluator = approvalEvaluator;
        this.planStore = planStore;
    }
```

- [ ] **Step 4: Add approval flow to `provision()` method**

Use `ide_replace_member` on `provision`:

```java
@Override
public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
    if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
        return new ProvisionResult.Failed("spec is not InfraDesiredNodeSpec");
    }

    if (context.hasApproval()) {
        return handleProvisionReEntry(node, wrapper, context);
    }

    var decision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
    if (decision instanceof ApprovalDecision.RequiresApproval req) {
        String ref = planStore.store(req.plan());
        return new ProvisionResult.PendingApproval(node.id(), ref);
    }

    return doProvision(wrapper, context);
}
```

- [ ] **Step 5: Extract `doProvision` and `doDeprovision` helpers**

Extract the existing provision/deprovision logic into private methods:

```java
private ProvisionResult doProvision(InfraDesiredNodeSpec wrapper, ProvisionContext context) {
    try {
        String clusterId = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
        InfraNodeSpec resourceSpec = wrapper.resourceSpec();
        @SuppressWarnings("unchecked")
        var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
        clientRegistry.withRetryOn401(clusterId, client -> {
            handler.apply(client, resourceSpec);
            return null;
        });
        return new ProvisionResult.Success();
    } catch (Exception e) {
        return new ProvisionResult.Failed(e.getMessage());
    }
}

private DeprovisionResult doDeprovision(InfraDesiredNodeSpec wrapper, DeprovisionContext context) {
    try {
        String clusterId = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
        InfraNodeSpec resourceSpec = wrapper.resourceSpec();
        @SuppressWarnings("unchecked")
        var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
        clientRegistry.withRetryOn401(clusterId, client -> {
            handler.delete(client, resourceSpec);
            return null;
        });
        return new DeprovisionResult.Success();
    } catch (Exception e) {
        return new DeprovisionResult.Failed(e.getMessage());
    }
}
```

- [ ] **Step 6: Add re-entry handling**

```java
private ProvisionResult handleProvisionReEntry(DesiredNode node, InfraDesiredNodeSpec wrapper,
                                                ProvisionContext context) {
    var planOpt = planStore.retrieve(context.approval().planReference());
    if (planOpt.isEmpty()) {
        var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
            String newRef = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), newRef);
        }
        return doProvision(wrapper, context);
    }

    var plan = planOpt.get();
    if (!plan.originalSpec().equals(node.spec())) {
        planStore.remove(context.approval().planReference());
        var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
            String newRef = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), newRef);
        }
        return doProvision(wrapper, context);
    }

    planStore.remove(context.approval().planReference());
    return doProvision(wrapper, context);
}
```

- [ ] **Step 7: Add approval flow to `deprovision()` and its re-entry**

Same pattern as provision. Use `ide_replace_member` on `deprovision`:

```java
@Override
public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
    if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
        return new DeprovisionResult.Failed("spec is not InfraDesiredNodeSpec");
    }

    if (context.hasApproval()) {
        return handleDeprovisionReEntry(node, wrapper, context);
    }

    var decision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
    if (decision instanceof ApprovalDecision.RequiresApproval req) {
        String ref = planStore.store(req.plan());
        return new DeprovisionResult.PendingApproval(node.id(), ref);
    }

    return doDeprovision(wrapper, context);
}

private DeprovisionResult handleDeprovisionReEntry(DesiredNode node, InfraDesiredNodeSpec wrapper,
                                                    DeprovisionContext context) {
    var planOpt = planStore.retrieve(context.approval().planReference());
    if (planOpt.isEmpty()) {
        var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
            String newRef = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), newRef);
        }
        return doDeprovision(wrapper, context);
    }

    var plan = planOpt.get();
    if (!plan.originalSpec().equals(node.spec())) {
        planStore.remove(context.approval().planReference());
        var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
            String newRef = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), newRef);
        }
        return doDeprovision(wrapper, context);
    }

    planStore.remove(context.approval().planReference());
    return doDeprovision(wrapper, context);
}
```

- [ ] **Step 8: Fix existing tests — update constructor calls**

Update existing tests in `KubernetesNodeProvisionerTest` to pass
`new K8sApprovalEvaluator()` and `new InMemoryPlanStore()` to the constructor.
All existing tests use low-risk operations (namespace provision via StubHandler)
so they should still auto-approve and pass.

- [ ] **Step 9: Add re-entry and stale-spec tests**

Add to `KubernetesNodeProvisionerTest`:

```java
@Test
void provision_withApproval_provisionsAndCleansPlan() {
    var planStore = new InMemoryPlanStore();
    var evaluator = new K8sApprovalEvaluator();
    var handler = new K8sHandlerRegistry();
    handler.register(new StubHandler(NodeStatus.ABSENT));
    var clientRegistry = new StubK8sClientRegistry();
    var provisioner = new KubernetesNodeProvisioner(handler, clientRegistry, evaluator, planStore);

    var spec = new InfraDesiredNodeSpec(
            new K8sNamespaceSpec("prod", Labels.empty()),
            "kubernetes:ops-prod");
    var node = new DesiredNode(NodeId.of("ns-1"),
            ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);
    var graph = new DefaultDesiredStateGraphFactory().empty();

    // Cycle 1: deprovision returns PendingApproval
    var result1 = provisioner.deprovision(node, new DeprovisionContext("tenant-1", graph));
    assertThat(result1).isInstanceOf(DeprovisionResult.PendingApproval.class);
    var pa = (DeprovisionResult.PendingApproval) result1;

    // Cycle 2: re-enter with approval
    var approval = new PlanApproval(pa.planReference(), "admin", java.time.Instant.now());
    var ctxApproved = new DeprovisionContext("tenant-1", graph, approval);
    var result2 = provisioner.deprovision(node, ctxApproved);
    assertThat(result2).isInstanceOf(DeprovisionResult.Success.class);

    // Plan cleaned up
    assertThat(planStore.retrieve(pa.planReference())).isEmpty();
}

@Test
void provision_staleSpec_reEvaluates() {
    var planStore = new InMemoryPlanStore();
    var evaluator = new K8sApprovalEvaluator();
    var handler = new K8sHandlerRegistry();
    handler.register(new StubHandler(NodeStatus.ABSENT));
    var clientRegistry = new StubK8sClientRegistry();
    var provisioner = new KubernetesNodeProvisioner(handler, clientRegistry, evaluator, planStore);

    var spec1 = new InfraDesiredNodeSpec(
            new K8sNamespaceSpec("prod", Labels.empty()),
            "kubernetes:ops-prod");
    var node1 = new DesiredNode(NodeId.of("ns-1"),
            ApplicationNodeTypes.K8S_NAMESPACE, spec1, HumanGating.NONE);
    var graph = new DefaultDesiredStateGraphFactory().empty();

    // Cycle 1: PendingApproval
    var result1 = provisioner.deprovision(node1, new DeprovisionContext("tenant-1", graph));
    var pa = (DeprovisionResult.PendingApproval) result1;

    // Spec changes
    var spec2 = new InfraDesiredNodeSpec(
            new K8sNamespaceSpec("prod-v2", Labels.empty()),
            "kubernetes:ops-prod");
    var node2 = new DesiredNode(NodeId.of("ns-1"),
            ApplicationNodeTypes.K8S_NAMESPACE, spec2, HumanGating.NONE);

    // Re-enter with approval but stale spec — should re-request approval
    var approval = new PlanApproval(pa.planReference(), "admin", java.time.Instant.now());
    var ctxApproved = new DeprovisionContext("tenant-1", graph, approval);
    var result2 = provisioner.deprovision(node2, ctxApproved);
    assertThat(result2).isInstanceOf(DeprovisionResult.PendingApproval.class);

    // Old plan cleaned up
    assertThat(planStore.retrieve(pa.planReference())).isEmpty();
}

@Test
void lowRiskProvision_autoApproves() {
    var planStore = new InMemoryPlanStore();
    var evaluator = new K8sApprovalEvaluator();
    var handler = new K8sHandlerRegistry();
    handler.register(new StubHandler(NodeStatus.ABSENT));
    var clientRegistry = new StubK8sClientRegistry();
    var provisioner = new KubernetesNodeProvisioner(handler, clientRegistry, evaluator, planStore);

    var spec = new InfraDesiredNodeSpec(
            new K8sConfigMapSpec("dev", "config", Map.of("k", "v"), Labels.empty()),
            "kubernetes:ops-dev");
    var node = new DesiredNode(NodeId.of("cm-1"),
            ApplicationNodeTypes.K8S_CONFIGMAP, spec, HumanGating.NONE);
    var graph = new DefaultDesiredStateGraphFactory().empty();

    var result = provisioner.provision(node, new ProvisionContext("tenant-1", graph));
    assertThat(result).isInstanceOf(ProvisionResult.Success.class);
}
```

- [ ] **Step 10: Run all provisioner tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=KubernetesNodeProvisionerTest`
Expected: All PASS

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/k8s/KubernetesNodeProvisioner.java app/src/test/java/io/casehub/ops/app/k8s/KubernetesNodeProvisionerTest.java
git commit -m "feat(#43): K8s provisioner approval flow — re-entry + stale-spec detection

High-risk K8s operations (namespace deletion, deployment deprovision) now
return PendingApproval. Re-entry verifies plan validity and spec freshness."
```

---

### Task 4: ApprovalResource REST implementation

**Files:**
- Modify: `app/src/main/java/io/casehub/ops/app/rest/ApprovalResource.java`
- Create: `app/src/test/java/io/casehub/ops/app/rest/ApprovalResourceTest.java`

**Interfaces:**
- Consumes: `WorkItemService.scan()`, `WorkItemService.findById()`,
  `WorkItemService.completeFromSystem()`, `WorkItemService.rejectFromSystem()`
  from `io.casehub.work.runtime.service`; `PlanStore` from `io.casehub.ops.api.approval`;
  `KubernetesEventSource.emitDrift()` from `io.casehub.ops.app.k8s`;
  `TenancyFilter.TENANCY_PROPERTY` from `io.casehub.ops.app.rest`
- Produces: REST endpoints `GET /api/approvals`, `GET /api/approvals/{id}`,
  `POST /api/approvals/{id}/approve`, `POST /api/approvals/{id}/reject`

- [ ] **Step 1: Create DTOs**

Add inner records to `ApprovalResource` (or as separate files):

```java
record ApproveRequest(String actorId) {}
record RejectRequest(String actorId, String reason) {}
record ApprovalView(
        UUID workItemId,
        String nodeId,
        String action,
        RiskClassification risk,
        String summary,
        String cluster,
        String namespace,
        String status,
        String assigneeId,
        Instant createdAt) {}
```

- [ ] **Step 2: Write failing test — list returns approval views**

Create `app/src/test/java/io/casehub/ops/app/rest/ApprovalResourceTest.java`.
This is a unit test with mock `WorkItemService` and `PlanStore`:

```java
package io.casehub.ops.app.rest;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.app.k8s.KubernetesEventSource;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.service.WorkItemService;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApprovalResourceTest {

    private WorkItemService workItemService;
    private PlanStore planStore;
    private KubernetesEventSource eventSource;
    private ApprovalResource resource;
    private ContainerRequestContext ctx;

    @BeforeEach
    void setUp() {
        workItemService = mock(WorkItemService.class);
        planStore = new InMemoryPlanStore();
        eventSource = new KubernetesEventSource();
        resource = new ApprovalResource(workItemService, planStore, eventSource);
        ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(TenancyFilter.TENANCY_PROPERTY)).thenReturn("tenant-1");
    }

    @Test
    void listApprovals_returnsEnrichedViews() {
        // Store a plan
        var plan = new ApprovalPlan(
                NodeId.of("ns-1"), StepAction.DEPROVISION, RiskClassification.CRITICAL,
                "Deprovision namespace 'prod'", "tenant-1",
                new InfraDesiredNodeSpec(new K8sNamespaceSpec("prod", Labels.empty()),
                        "kubernetes:ops-prod"),
                null);
        String planRef = planStore.store(plan);

        // Mock a WorkItem
        var workItem = new WorkItem();
        workItem.id = UUID.randomUUID();
        workItem.payload = planRef;
        workItem.tenancyId = "tenant-1";
        workItem.title = "Approve deprovision: ns-1";
        workItem.createdAt = Instant.now();
        when(workItemService.scan(any())).thenReturn(List.of(workItem));

        var response = resource.listApprovals(ctx);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
```

Note: check Mockito availability — if not a test dependency, add it or use
a stub `WorkItemService` instead.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn --batch-mode -o test -pl app -Dtest=ApprovalResourceTest#listApprovals_returnsEnrichedViews`
Expected: FAIL — `ApprovalResource` constructor doesn't accept these params.

- [ ] **Step 4: Implement ApprovalResource**

Replace the entire `ApprovalResource` class using `ide_edit_member` with `member = "ApprovalResource"`:

```java
@Blocking
@ApplicationScoped
@Path("/api/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApprovalResource {

    private final WorkItemService workItemService;
    private final PlanStore planStore;
    private final KubernetesEventSource eventSource;

    @Inject
    public ApprovalResource(WorkItemService workItemService,
                            PlanStore planStore,
                            KubernetesEventSource eventSource) {
        this.workItemService = workItemService;
        this.planStore = planStore;
        this.eventSource = eventSource;
    }

    @GET
    public Response listApprovals(@Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var items = workItemService.scan(
                WorkItemQuery.builder()
                        .type("desiredstate-approval")
                        .tenancyId(tenancyId)
                        .build());
        var views = items.stream().map(this::toView).toList();
        return Response.ok(views).build();
    }

    @GET
    @Path("/{id}")
    public Response getApproval(@PathParam("id") UUID id,
                                @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var item = workItemService.findById(id).orElse(null);
        if (item == null) return Response.status(Response.Status.NOT_FOUND).build();
        if (!tenancyId.equals(item.tenancyId)) return Response.status(Response.Status.FORBIDDEN).build();
        return Response.ok(toView(item)).build();
    }

    @POST
    @Path("/{id}/approve")
    public Response approve(@PathParam("id") UUID id,
                            ApproveRequest request,
                            @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var item = workItemService.findById(id).orElse(null);
        if (item == null) return Response.status(Response.Status.NOT_FOUND).build();
        if (!tenancyId.equals(item.tenancyId)) return Response.status(Response.Status.FORBIDDEN).build();

        workItemService.completeFromSystem(id, request.actorId(), "approve");

        if (item.payload != null) {
            planStore.retrieve(item.payload).ifPresent(plan ->
                    eventSource.emitDrift(plan.nodeId()));
        }

        return Response.accepted().build();
    }

    @POST
    @Path("/{id}/reject")
    public Response reject(@PathParam("id") UUID id,
                           RejectRequest request,
                           @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var item = workItemService.findById(id).orElse(null);
        if (item == null) return Response.status(Response.Status.NOT_FOUND).build();
        if (!tenancyId.equals(item.tenancyId)) return Response.status(Response.Status.FORBIDDEN).build();

        workItemService.rejectFromSystem(id, request.actorId(), request.reason());
        return Response.accepted().build();
    }

    private ApprovalView toView(WorkItem item) {
        var planOpt = item.payload != null
                ? planStore.retrieve(item.payload) : Optional.<ApprovalPlan>empty();
        if (planOpt.isPresent()) {
            var plan = planOpt.get();
            String cluster = null;
            String namespace = null;
            if (plan.originalSpec() instanceof InfraDesiredNodeSpec wrapper) {
                cluster = wrapper.backendId();
                namespace = extractNamespace(wrapper.resourceSpec());
            }
            return new ApprovalView(
                    item.id, plan.nodeId().value(), plan.action().name(),
                    plan.risk(), plan.summary(), cluster, namespace,
                    item.status.name(), item.assigneeId, item.createdAt);
        }
        return new ApprovalView(
                item.id, null, null, null, item.title,
                null, null, item.status.name(), item.assigneeId, item.createdAt);
    }

    private String extractNamespace(InfraNodeSpec spec) {
        return switch (spec) {
            case K8sNamespaceSpec s -> s.name();
            case K8sDeploymentSpec s -> s.namespace();
            case K8sServiceSpec s -> s.namespace();
            case K8sIngressSpec s -> s.namespace();
            case K8sConfigMapSpec s -> s.namespace();
            default -> null;
        };
    }

    record ApproveRequest(String actorId) {}
    record RejectRequest(String actorId, String reason) {}
    record ApprovalView(UUID workItemId, String nodeId, String action,
                        RiskClassification risk, String summary, String cluster,
                        String namespace, String status, String assigneeId,
                        Instant createdAt) {}
}
```

- [ ] **Step 5: Add tenancy isolation tests**

Add to `ApprovalResourceTest`:

```java
@Test
void approve_wrongTenancy_returns403() {
    var workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.tenancyId = "tenant-2";
    when(workItemService.findById(workItem.id)).thenReturn(Optional.of(workItem));

    var response = resource.approve(workItem.id, new ApprovalResource.ApproveRequest("admin"), ctx);

    assertThat(response.getStatus()).isEqualTo(403);
    verify(workItemService, never()).completeFromSystem(any(), any(), any());
}

@Test
void reject_wrongTenancy_returns403() {
    var workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.tenancyId = "tenant-2";
    when(workItemService.findById(workItem.id)).thenReturn(Optional.of(workItem));

    var response = resource.reject(workItem.id,
            new ApprovalResource.RejectRequest("admin", "not needed"), ctx);

    assertThat(response.getStatus()).isEqualTo(403);
    verify(workItemService, never()).rejectFromSystem(any(), any(), any());
}

@Test
void approve_notFound_returns404() {
    when(workItemService.findById(any())).thenReturn(Optional.empty());

    var response = resource.approve(UUID.randomUUID(),
            new ApprovalResource.ApproveRequest("admin"), ctx);

    assertThat(response.getStatus()).isEqualTo(404);
}

@Test
void approve_triggersReconciliation() {
    var plan = new ApprovalPlan(
            NodeId.of("ns-1"), StepAction.DEPROVISION, RiskClassification.CRITICAL,
            "Deprovision namespace 'prod'", "tenant-1",
            new InfraDesiredNodeSpec(new K8sNamespaceSpec("prod", Labels.empty()),
                    "kubernetes:ops-prod"),
            null);
    String planRef = planStore.store(plan);

    var workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.payload = planRef;
    workItem.tenancyId = "tenant-1";
    when(workItemService.findById(workItem.id)).thenReturn(Optional.of(workItem));

    resource.approve(workItem.id, new ApprovalResource.ApproveRequest("admin"), ctx);

    verify(workItemService).completeFromSystem(workItem.id, "admin", "approve");
    // eventSource.emitDrift was called — verify via subscription or field check
}
```

- [ ] **Step 6: Run all resource tests**

Run: `mvn --batch-mode -o test -pl app -Dtest=ApprovalResourceTest`
Expected: All PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/casehub/ops/app/rest/ApprovalResource.java app/src/test/java/io/casehub/ops/app/rest/ApprovalResourceTest.java
git commit -m "feat(#43): ApprovalResource REST API — list, approve, reject with tenancy isolation

WorkItem-backed approval endpoints with plan enrichment, cross-tenancy
403 guard, and immediate reconciliation trigger on approve."
```

---

### Task 5: Update integration tests

**Files:**
- Modify: `deployment/src/test/java/io/casehub/ops/deployment/ApprovalLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: `MockPendingApprovalHandler` from `casehub-desiredstate-testing`,
  or `WorkItemPendingApprovalHandler` from `casehub-desiredstate-work`

- [ ] **Step 1: Read MockPendingApprovalHandler API**

Use `ide_file_structure` on `src/main/java/io/casehub/desiredstate/testing/MockPendingApprovalHandler.java`
in the desiredstate project to understand its approve/reject simulation API.

- [ ] **Step 2: Rewrite ApprovalLifecycleIntegrationTest**

The existing test verifies the full provisioner approval lifecycle
(happy path, auto-approve, rejection, stale spec, deprovision). Update
it to use `MockPendingApprovalHandler` instead of the deleted
`OpsPendingApprovalHandler`. The provisioner's behavior is unchanged —
only the handler wiring changes.

Adapt the approve/reject calls to match `MockPendingApprovalHandler`'s API
(discovered in Step 1). If MockPendingApprovalHandler doesn't support
programmatic approve/reject, use the `InMemoryWorkItemCreator` pattern from
`WorkItemPendingApprovalHandlerTest` in casehub-desiredstate-work.

- [ ] **Step 3: Run integration tests**

Run: `mvn --batch-mode -o test -pl deployment -Dtest=ApprovalLifecycleIntegrationTest`
Expected: All 5 scenarios PASS

- [ ] **Step 4: Run full test suite**

Run: `mvn --batch-mode install`
Expected: All modules build, all tests pass (284+ app tests + domain tests).

- [ ] **Step 5: Commit**

```bash
git add deployment/src/test/java/io/casehub/ops/deployment/ApprovalLifecycleIntegrationTest.java
git commit -m "test(#43): rewrite approval integration test for WorkItem-backed handler

Replaces deleted OpsPendingApprovalHandler with MockPendingApprovalHandler.
Same 5 lifecycle scenarios, same assertions."
```

---

## Self-Review

**Spec coverage:**
- ✅ Change 1: `casehub-desiredstate-work` dependency → Task 1 Step 1
- ✅ Change 2: Delete `OpsPendingApprovalHandler` → Task 1 Steps 2-3
- ✅ Change 2 atomicity: same commit → Task 1 Step 6
- ✅ Change 3: `K8sApprovalEvaluator` → Task 2
- ✅ Change 4: `KubernetesNodeProvisioner` approval flow → Task 3
- ✅ Change 5: `ApprovalResource` REST implementation → Task 4
- ✅ Change 5 auth: tenancy isolation → Task 4 Steps 5
- ✅ Change 5 reconciliation trigger → Task 4 Step 4 (approve endpoint)
- ✅ Change 6: Integration test update → Task 5

**Placeholder scan:** No TBDs, TODOs, or "implement later" markers.

**Type consistency:** `ApprovalDecision.RequiresApproval`, `ApprovalPlan`, `PlanStore`,
`InfraDesiredNodeSpec` types used consistently across Tasks 2-4.

**Tooling safety scan:** No bash file operations on source files. All code changes
use `ide_edit_member`, `ide_replace_member`, `ide_insert_member`, or `ide_create_file`.
File deletions use `ide_refactor_safe_delete`.
