package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class ComplianceActualStateAdapter implements ActualStateAdapter {

    private final ComplianceEvidenceService evidenceService;
    private final ComplianceSpecHashStore specHashStore;
    private final String tenancyId;

    @Inject
    public ComplianceActualStateAdapter(
            ComplianceEvidenceService evidenceService,
            ComplianceSpecHashStore specHashStore) {
        this.evidenceService = evidenceService;
        this.specHashStore = specHashStore;
        this.tenancyId = "default";
    }

    ComplianceActualStateAdapter(
            ComplianceEvidenceService evidenceService,
            ComplianceSpecHashStore specHashStore,
            String tenancyId) {
        this.evidenceService = evidenceService;
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
        ComplianceControlSpec spec = (ComplianceControlSpec) node.spec();
        ControlEvidenceStatus status = evidenceService.evidenceStatus(spec, tenancyId);

        if (status.derivedNodeStatus() == NodeStatus.PRESENT
                && specHashStore.hasDrifted(node.id(), node.spec())) {
            return NodeStatus.DRIFTED;
        }
        return status.derivedNodeStatus();
    }
}
