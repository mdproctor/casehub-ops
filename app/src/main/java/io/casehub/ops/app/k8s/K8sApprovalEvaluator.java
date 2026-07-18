package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sApprovalEvaluator implements ApprovalEvaluator {

    private static final ApprovalThresholds THRESHOLDS =
            new ApprovalThresholds(RiskClassification.HIGH);

    @Override
    public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ApprovalDecision.AutoApproved();
        }

        RiskClassification risk = classifyRisk(node.type(), action);
        if (!THRESHOLDS.requiresApproval(risk)) {
            return new ApprovalDecision.AutoApproved();
        }

        var plan = new ApprovalPlan(
                node.id(), action, risk,
                generateSummary(wrapper, action),
                tenancyId, node.spec(), null);
        return new ApprovalDecision.RequiresApproval(plan);
    }

    private RiskClassification classifyRisk(NodeType type, StepAction action) {
        if (type.equals(ApplicationNodeTypes.K8S_NAMESPACE)) {
            return action == StepAction.DEPROVISION
                    ? RiskClassification.CRITICAL : RiskClassification.LOW;
        }
        if (type.equals(ApplicationNodeTypes.K8S_DEPLOYMENT)) {
            return action == StepAction.DEPROVISION
                    ? RiskClassification.HIGH : RiskClassification.MEDIUM;
        }
        if (type.equals(ApplicationNodeTypes.K8S_SERVICE)
                || type.equals(ApplicationNodeTypes.K8S_INGRESS)
                || type.equals(ApplicationNodeTypes.K8S_CONFIGMAP)) {
            return action == StepAction.DEPROVISION
                    ? RiskClassification.MEDIUM : RiskClassification.LOW;
        }
        return RiskClassification.LOW;
    }

    private String generateSummary(InfraDesiredNodeSpec wrapper, StepAction action) {
        String verb = action == StepAction.PROVISION ? "Provision" : "Deprovision";
        String cluster = wrapper.backendId();
        return switch (wrapper.resourceSpec()) {
            case K8sNamespaceSpec s ->
                    verb + " namespace '" + s.name() + "' on " + cluster;
            case K8sDeploymentSpec s ->
                    verb + " deployment '" + s.namespace() + "/" + s.name()
                            + "' (" + s.image() + ", " + s.replicas() + " replicas) on " + cluster;
            case K8sServiceSpec s ->
                    verb + " service '" + s.namespace() + "/" + s.name()
                            + "' (port " + s.port() + ") on " + cluster;
            case K8sIngressSpec s ->
                    verb + " ingress '" + s.namespace() + "/" + s.name()
                            + "' (host: " + s.host() + ") on " + cluster;
            case K8sConfigMapSpec s ->
                    verb + " configmap '" + s.namespace() + "/" + s.name() + "' on " + cluster;
            default -> verb + " " + wrapper.resourceSpec().resourceType() + " on " + cluster;
        };
    }
}
