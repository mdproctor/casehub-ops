package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesNodeProvisionerTest {

    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();
    private static final io.casehub.ops.api.approval.ApprovalEvaluator AUTO_APPROVE =
            (node, action, tenancyId) -> new io.casehub.ops.api.approval.ApprovalDecision.AutoApproved();


    @Test
    void provisionDelegatesToHandler() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, AUTO_APPROVE, new io.casehub.ops.api.approval.InMemoryPlanStore());
        var context = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(handler.applyCalled).isTrue();
    }

    @Test
    void deprovisionDelegatesToHandler() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, AUTO_APPROVE, new io.casehub.ops.api.approval.InMemoryPlanStore());
        var context = new DeprovisionContext("default", graph);

        DeprovisionResult result = provisioner.deprovision(node, context);
        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(handler.deleteCalled).isTrue();
    }

    @Test
    void provisionFailsWhenHandlerThrows() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubHandler(NodeStatus.PRESENT) {
            @Override
            public void apply(KubernetesClient client, K8sNamespaceSpec spec) {
                throw new RuntimeException("cluster down");
            }
        };
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, AUTO_APPROVE, new io.casehub.ops.api.approval.InMemoryPlanStore());
        var context = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void handledTypesReturnsFiveK8sTypes() {
        var handlerRegistry = new K8sHandlerRegistry(List.of(new StubHandler(NodeStatus.PRESENT)));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, AUTO_APPROVE, new io.casehub.ops.api.approval.InMemoryPlanStore());
        assertThat(provisioner.handledTypes()).hasSize(5);
    }

    /**
     * Minimal stub handler for testing — returns a fixed status without touching K8s.
     */
    static class StubHandler implements K8sResourceHandler<K8sNamespaceSpec> {
        private final NodeStatus statusToReturn;
        boolean readStatusCalled;
        boolean applyCalled;
        boolean deleteCalled;

        StubHandler(NodeStatus statusToReturn) {
            this.statusToReturn = statusToReturn;
        }

        @Override
        public Class<K8sNamespaceSpec> specType() {
            return K8sNamespaceSpec.class;
        }

        @Override
        public HasMetadata toResource(K8sNamespaceSpec spec) {
            return null;
        }

        @Override
        public NodeStatus readStatus(KubernetesClient client, K8sNamespaceSpec spec) {
            readStatusCalled = true;
            return statusToReturn;
        }

        @Override
        public void apply(KubernetesClient client, K8sNamespaceSpec spec) {
            applyCalled = true;
        }

        @Override
        public void delete(KubernetesClient client, K8sNamespaceSpec spec) {
            deleteCalled = true;
        }
    }

    @Test
    void provisionRetriesOn401AfterCredentialRefresh() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new StubHandler(NodeStatus.PRESENT) {
            @Override
            public void apply(KubernetesClient client, K8sNamespaceSpec spec) {
                if (callCount.getAndIncrement() == 0) {
                    throw new KubernetesClientException("Unauthorized", 401, null);
                }
                applyCalled = true;
            }
        };
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, AUTO_APPROVE, new io.casehub.ops.api.approval.InMemoryPlanStore());
        var context = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void deprovisionRetriesOn401AfterCredentialRefresh() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        var handler = new StubHandler(NodeStatus.PRESENT) {
            @Override
            public void delete(KubernetesClient client, K8sNamespaceSpec spec) {
                if (callCount.getAndIncrement() == 0) {
                    throw new KubernetesClientException("Unauthorized", 401, null);
                }
                deleteCalled = true;
            }
        };
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, AUTO_APPROVE, new io.casehub.ops.api.approval.InMemoryPlanStore());
        var context = new DeprovisionContext("default", graph);

        DeprovisionResult result = provisioner.deprovision(node, context);
        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void deprovision_highRisk_returnsPendingApproval() {
        var planStore       = new io.casehub.ops.api.approval.InMemoryPlanStore();
        var handler         = new StubHandler(NodeStatus.ABSENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry  = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, new K8sApprovalEvaluator(), planStore);

        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod-billing", Labels.of(Map.of())),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("ns-1"),
                                   ApplicationNodeTypes.K8S_NAMESPACE, spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.empty();

        var result = provisioner.deprovision(node, new DeprovisionContext("tenant-1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.PendingApproval.class);
        var pa = (DeprovisionResult.PendingApproval) result;
        assertThat(planStore.retrieve(pa.planReference())).isPresent();
    }

    @Test
    void deprovision_withApproval_deprovisions_andCleansPlan() {
        var planStore       = new io.casehub.ops.api.approval.InMemoryPlanStore();
        var handler         = new StubHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry  = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, new K8sApprovalEvaluator(), planStore);

        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod", Labels.of(Map.of())),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("ns-1"),
                                   ApplicationNodeTypes.K8S_NAMESPACE, spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.empty();

        var result1 = provisioner.deprovision(node, new DeprovisionContext("tenant-1", graph));
        assertThat(result1).isInstanceOf(DeprovisionResult.PendingApproval.class);
        var pa = (DeprovisionResult.PendingApproval) result1;

        var approval    = new io.casehub.desiredstate.api.PlanApproval(pa.planReference(), "admin", java.time.Instant.now());
        var ctxApproved = new DeprovisionContext("tenant-1", graph, approval);
        var result2     = provisioner.deprovision(node, ctxApproved);
        assertThat(result2).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(handler.deleteCalled).isTrue();
        assertThat(planStore.retrieve(pa.planReference())).isEmpty();
    }

    @Test
    void deprovision_staleSpec_reEvaluates() {
        var planStore       = new io.casehub.ops.api.approval.InMemoryPlanStore();
        var handler         = new StubHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry  = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, new K8sApprovalEvaluator(), planStore);

        var spec1 = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod", Labels.of(Map.of())),
                "kubernetes:ops-prod");
        var node1 = new DesiredNode(NodeId.of("ns-1"),
                                    ApplicationNodeTypes.K8S_NAMESPACE, spec1, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.empty();

        var result1 = provisioner.deprovision(node1, new DeprovisionContext("tenant-1", graph));
        var pa      = (DeprovisionResult.PendingApproval) result1;

        var spec2 = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod-v2", Labels.of(Map.of())),
                "kubernetes:ops-prod");
        var node2 = new DesiredNode(NodeId.of("ns-1"),
                                    ApplicationNodeTypes.K8S_NAMESPACE, spec2, io.casehub.desiredstate.api.HumanGating.NONE);

        var approval    = new io.casehub.desiredstate.api.PlanApproval(pa.planReference(), "admin", java.time.Instant.now());
        var ctxApproved = new DeprovisionContext("tenant-1", graph, approval);
        var result2     = provisioner.deprovision(node2, ctxApproved);
        assertThat(result2).isInstanceOf(DeprovisionResult.PendingApproval.class);
        assertThat(planStore.retrieve(pa.planReference())).isEmpty();
    }

    @Test
    void lowRiskProvision_autoApproves() {
        var planStore       = new io.casehub.ops.api.approval.InMemoryPlanStore();
        var handler         = new StubHandler(NodeStatus.ABSENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry  = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-dev", "https://localhost:6443");
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry, new K8sApprovalEvaluator(), planStore);

        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("dev-sandbox", Labels.of(Map.of())),
                "kubernetes:ops-dev");
        var node = new DesiredNode(NodeId.of("ns-1"),
                                   ApplicationNodeTypes.K8S_NAMESPACE, spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.empty();

        var result = provisioner.provision(node, new ProvisionContext("tenant-1", graph));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(handler.applyCalled).isTrue();
    }

}
