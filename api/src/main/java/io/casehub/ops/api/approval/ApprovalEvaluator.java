package io.casehub.ops.api.approval;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.StepAction;

public interface ApprovalEvaluator {
    ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId);
}
