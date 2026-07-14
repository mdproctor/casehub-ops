package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;

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
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.client.KubernetesClientException;

import static org.assertj.core.api.Assertions.*;

class KubernetesNodeProvisionerTest {

    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    @Test
    void provisionDelegatesToHandler() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
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
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
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
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
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

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
        var context = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void handledTypesReturnsFiveK8sTypes() {
        var handlerRegistry = new K8sHandlerRegistry(List.of(new StubHandler(NodeStatus.PRESENT)));
        var clientRegistry = new K8sClientRegistry(ref -> Map.of());
        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
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
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
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

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
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
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
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

        var provisioner = new KubernetesNodeProvisioner(handlerRegistry, clientRegistry);
        var context = new DeprovisionContext("default", graph);

        DeprovisionResult result = provisioner.deprovision(node, context);
        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(callCount.get()).isEqualTo(2);
    }
}
