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
