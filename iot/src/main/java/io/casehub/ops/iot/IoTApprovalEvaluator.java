package io.casehub.ops.iot;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;

@ApplicationScoped
public class IoTApprovalEvaluator implements ApprovalEvaluator {

    @Override
    public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
        return new ApprovalDecision.AutoApproved();
    }
}
