package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.EvidenceOutcome;
import java.time.Instant;

public record ControlEvidenceStatus(
        String controlId,
        String controlType,
        EvidenceOutcome latestOutcome,
        Instant latestEvidenceAt,
        int evidenceMaxAgeDays,
        boolean stale,
        NodeStatus derivedNodeStatus
) {
    public static ControlEvidenceStatus absent(
            String controlId, String controlType, int evidenceMaxAgeDays) {
        return new ControlEvidenceStatus(
                controlId, controlType, null, null,
                evidenceMaxAgeDays, false, NodeStatus.ABSENT);
    }
}
