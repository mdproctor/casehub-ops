package io.casehub.ops.deployment;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.deployment.*;

@ApplicationScoped
public class DeploymentApprovalEvaluator implements ApprovalEvaluator {

    private static final ApprovalThresholds THRESHOLDS =
            new ApprovalThresholds(RiskClassification.HIGH);

    @Override
    public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
        if (!(node.spec() instanceof DeploymentNodeSpec spec)) {
            return new ApprovalDecision.AutoApproved();
        }

        RiskClassification risk = classifyRisk(spec);
        if (!THRESHOLDS.requiresApproval(risk)) {
            return new ApprovalDecision.AutoApproved();
        }

        var plan = new ApprovalPlan(
                node.id(), action, risk,
                generateSummary(spec, action),
                tenancyId, node.spec(), null);
        return new ApprovalDecision.RequiresApproval(plan);
    }

    private RiskClassification classifyRisk(DeploymentNodeSpec spec) {
        return switch (spec) {
            case TrustPolicyNodeSpec s -> RiskClassification.HIGH;
            case AgentNodeSpec s -> RiskClassification.MEDIUM;
            case ChannelNodeSpec s -> RiskClassification.LOW;
            case CaseTypeNodeSpec s -> RiskClassification.LOW;
            case EndpointNodeSpec s -> RiskClassification.LOW;
        };
    }

    private String generateSummary(DeploymentNodeSpec spec, StepAction action) {
        String verb = action == StepAction.PROVISION ? "Provision" : "Deprovision";
        return switch (spec) {
            case TrustPolicyNodeSpec s -> verb + " trust policy '" + s.capability()
                    + "': threshold=" + s.threshold() + ", blendFactor=" + s.blendFactor();
            case AgentNodeSpec s -> verb + " agent '" + s.agentId()
                    + "' (" + s.provider() + "/" + s.modelFamily() + ")";
            case ChannelNodeSpec s -> verb + " channel '" + s.name() + "'";
            case CaseTypeNodeSpec s -> verb + " case type '" + s.namespace() + "/" + s.name() + "'";
            case EndpointNodeSpec s -> verb + " endpoint '" + s.path() + "'";
        };
    }
}
