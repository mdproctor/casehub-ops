package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ComplianceActualStateAdapter implements ActualStateAdapter {

    private final ComplianceEvidenceService evidenceService;
    private final ComplianceSpecHashStore specHashStore;

    @Inject
    public ComplianceActualStateAdapter(
            ComplianceEvidenceService evidenceService,
            ComplianceSpecHashStore specHashStore) {
        this.evidenceService = evidenceService;
        this.specHashStore = specHashStore;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return ComplianceNodeProvisioner.HANDLED_TYPES;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var node : desired.nodes().values()) {
            statuses.put(node.id(), checkNode(node, tenancyId));
        }
        return new ActualState(statuses);
    }

    private NodeStatus checkNode(DesiredNode node, String tenancyId) {
        ComplianceControlSpec spec = (ComplianceControlSpec) node.spec();
        ControlEvidenceStatus status = evidenceService.evidenceStatus(spec, tenancyId);

        if (status.derivedNodeStatus() == NodeStatus.PRESENT
                && specHashStore.hasDrifted(node.id(), node.spec())) {
            return NodeStatus.DRIFTED;
        }
        return status.derivedNodeStatus();
    }
}
