package io.casehub.ops.container;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.ops.container.podman.PodmanClient;
import io.casehub.ops.container.podman.PodmanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class PodmanActualStateAdapter implements ActualStateAdapter {

    private static final Set<NodeType> HANDLED = Set.of(
        ContainerNodeTypes.APP_CONTAINER, ContainerNodeTypes.DATABASE,
        ContainerNodeTypes.NETWORK, ContainerNodeTypes.VOLUME);

    private final PodmanClient podmanClient;

    @Inject
    public PodmanActualStateAdapter(PodmanClient podmanClient) {
        this.podmanClient = podmanClient;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return HANDLED;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        var containers = podmanClient.listContainers().await().indefinitely();
        var networks = podmanClient.listNetworks().await().indefinitely();
        var volumes = podmanClient.listVolumes().await().indefinitely();

        var containersByName = new HashMap<String, PodmanContainer>();
        for (var c : containers) {
            for (var name : c.names()) {
                containersByName.put(name, c);
            }
        }
        var networkNames = new java.util.HashSet<String>();
        for (var n : networks) networkNames.add(n.name());

        var volumeNames = new java.util.HashSet<String>();
        for (var v : volumes) volumeNames.add(v.name());

        var statuses = new HashMap<NodeId, NodeStatus>();

        for (var entry : desired.nodes().entrySet()) {
            DesiredNode node = entry.getValue();
            NodeType type = node.type();

            if (!HANDLED.contains(type)) continue;

            if (type.equals(ContainerNodeTypes.APP_CONTAINER) || type.equals(ContainerNodeTypes.DATABASE)) {
                String name = extractContainerName(node);
                var container = containersByName.get(name);
                if (container == null) {
                    statuses.put(entry.getKey(), NodeStatus.ABSENT);
                } else if ("running".equals(container.state())) {
                    statuses.put(entry.getKey(), NodeStatus.PRESENT);
                } else {
                    statuses.put(entry.getKey(), NodeStatus.DRIFTED);
                }
            } else if (type.equals(ContainerNodeTypes.NETWORK)) {
                String name = ((NetworkSpec) node.spec()).name();
                statuses.put(entry.getKey(), networkNames.contains(name) ? NodeStatus.PRESENT : NodeStatus.ABSENT);
            } else if (type.equals(ContainerNodeTypes.VOLUME)) {
                String name = ((VolumeSpec) node.spec()).name();
                statuses.put(entry.getKey(), volumeNames.contains(name) ? NodeStatus.PRESENT : NodeStatus.ABSENT);
            }
        }

        return new ActualState(statuses);
    }

    private String extractContainerName(DesiredNode node) {
        return switch (node.spec()) {
            case AppContainerSpec s -> s.name();
            case DatabaseContainerSpec s -> s.name();
            default -> throw new IllegalArgumentException("Not a container spec: " + node.spec());
        };
    }
}
