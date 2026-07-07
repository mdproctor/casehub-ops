package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class KubernetesActualStateAdapterTest {

    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    @Test
    void readActualDelegatesToHandlerPerNode() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:ops-prod");
        var nodeId = NodeId.of("ops-prod:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubNamespaceHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();
        clientRegistry.register("ops-prod", "https://localhost:6443");

        var adapter = new KubernetesActualStateAdapter(handlerRegistry, clientRegistry);
        var actual = adapter.readActual(graph, "default");

        assertThat(actual.statusOf(nodeId)).contains(NodeStatus.PRESENT);
        assertThat(handler.readStatusCalled).isTrue();
    }

    @Test
    void returnsUnknownWhenClusterNotRegistered() {
        var nsSpec = new K8sNamespaceSpec("casehub", Labels.of(Map.of()));
        var wrappedSpec = new InfraDesiredNodeSpec(nsSpec, "kubernetes:unknown-cluster");
        var nodeId = NodeId.of("unknown-cluster:namespace");
        var node = new DesiredNode(nodeId, ApplicationNodeTypes.K8S_NAMESPACE, wrappedSpec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var handler = new StubNamespaceHandler(NodeStatus.PRESENT);
        var handlerRegistry = new K8sHandlerRegistry(List.of(handler));
        var clientRegistry = new K8sClientRegistry();

        var adapter = new KubernetesActualStateAdapter(handlerRegistry, clientRegistry);
        var actual = adapter.readActual(graph, "default");

        assertThat(actual.statusOf(nodeId)).contains(NodeStatus.UNKNOWN);
    }

    /**
     * Minimal stub handler for testing — returns a fixed status without touching K8s.
     */
    static class StubNamespaceHandler implements K8sResourceHandler<K8sNamespaceSpec> {
        private final NodeStatus statusToReturn;
        boolean readStatusCalled;
        boolean applyCalled;
        boolean deleteCalled;

        StubNamespaceHandler(NodeStatus statusToReturn) {
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
}
