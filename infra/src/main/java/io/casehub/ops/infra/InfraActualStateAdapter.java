package io.casehub.ops.infra;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.spi.InfraBackend;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads actual infrastructure state by delegating per-node readState() calls
 * to the correct {@link InfraBackend} based on each node's {@code backendId}.
 *
 * <p>Iterates nodes sequentially — each readState() call blocks via
 * {@code await().indefinitely()}. For large graphs this is O(N) sequential I/O.
 * A reactive adapter would fan out concurrent readState() calls. This is a known
 * limitation of the blocking {@link ActualStateAdapter} SPI.
 */
@ApplicationScoped
public class InfraActualStateAdapter implements ActualStateAdapter {

    private final Map<String, InfraBackend> backends;

    @Inject
    public InfraActualStateAdapter(@Any Instance<InfraBackend> backends) {
        this.backends = backends.stream()
                .collect(Collectors.toMap(InfraBackend::backendId, b -> b));
    }

    /** Test constructor — accepts an explicit list of backends. */
    InfraActualStateAdapter(List<InfraBackend> backends) {
        this.backends = backends.stream()
                .collect(Collectors.toMap(InfraBackend::backendId, b -> b));
    }

    public Set<NodeType> handledTypes() {
        return Set.of(
                NodeType.of("k8s_namespace"),
                NodeType.of("k8s_deployment"),
                NodeType.of("k8s_service"),
                NodeType.of("k8s_ingress"),
                NodeType.of("compute_instance"),
                NodeType.of("database_cluster"),
                NodeType.of("terraform_workspace"),
                NodeType.of("ansible_playbook"));
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var node : desired.nodes().values()) {
            statuses.put(node.id(), readNodeStatus(node));
        }
        return new ActualState(statuses);
    }

    private NodeStatus readNodeStatus(DesiredNode node) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return NodeStatus.UNKNOWN;
        }

        var backend = backends.get(wrapper.backendId());
        if (backend == null) {
            return NodeStatus.UNKNOWN;
        }

        try {
            ResourceState state = backend.readState(node.id(), wrapper.resourceSpec()).await().indefinitely();
            return mapStatus(state.status());
        } catch (Exception e) {
            return NodeStatus.UNKNOWN;
        }}

    private NodeStatus mapStatus(ResourceStatus status) {
        return switch (status) {
            case HEALTHY -> NodeStatus.PRESENT;
            case DRIFTED -> NodeStatus.DRIFTED;
            case UNAVAILABLE -> NodeStatus.ABSENT;
            default -> NodeStatus.UNKNOWN;
        };
    }
}
