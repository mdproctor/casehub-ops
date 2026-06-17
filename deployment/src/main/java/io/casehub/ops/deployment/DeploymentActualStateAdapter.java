package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DeploymentActualStateAdapter implements ActualStateAdapter {

    private final Map<String, NodeDriftChecker> checkers;
    private final SpecHashStore specHashStore;
    private final String tenancyId;

    @Inject
    public DeploymentActualStateAdapter(
            Instance<NodeDriftChecker> driftCheckers,
            SpecHashStore specHashStore) {
        this.checkers = new HashMap<>();
        for (var checker : driftCheckers) {
            this.checkers.put(checker.nodeType(), checker);
        }
        this.specHashStore = specHashStore;
        this.tenancyId = "default";
    }

    // Test constructor
    DeploymentActualStateAdapter(List<NodeDriftChecker> driftCheckers, SpecHashStore specHashStore) {
        this(driftCheckers, specHashStore, "default");
    }

    // Test constructor with custom tenancyId
    DeploymentActualStateAdapter(List<NodeDriftChecker> driftCheckers, SpecHashStore specHashStore, String tenancyId) {
        this.checkers = new HashMap<>();
        for (var checker : driftCheckers) {
            this.checkers.put(checker.nodeType(), checker);
        }
        this.specHashStore = specHashStore;
        this.tenancyId = tenancyId;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var node : desired.nodes().values()) {
            statuses.put(node.id(), checkNode(node));
        }
        return new ActualState(statuses);
    }

    private NodeStatus checkNode(DesiredNode node) {
        // Layer 1: external — does the node exist and match in the foundation module?
        NodeDriftChecker checker = checkers.get(node.type().value());
        NodeStatus external = (checker != null)
                ? checker.check(node.spec(), tenancyId)
                : NodeStatus.UNKNOWN;

        if (external == NodeStatus.ABSENT) return NodeStatus.ABSENT;
        if (external == NodeStatus.DRIFTED) return NodeStatus.DRIFTED;

        // Layer 2: spec hash — only for PRESENT nodes, did the declaration change?
        if (external == NodeStatus.PRESENT && specHashStore.hasDrifted(node.id(), node.spec())) {
            return NodeStatus.DRIFTED;
        }
        return external;  // PRESENT or UNKNOWN — unchanged
    }
}
