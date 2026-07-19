package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.ApprovalThresholds;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sConfigMapSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sIngressSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sApprovalEvaluator implements ApprovalEvaluator {

    private static final ApprovalThresholds THRESHOLDS =
            new ApprovalThresholds(RiskClassification.HIGH);

    private final java.util.Set<String> criticalNamespaces;
    private final java.util.Set<String> productionNamespaces;

    @jakarta.inject.Inject
    public K8sApprovalEvaluator(
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                    name = "casehub.ops.k8s.approval.critical-namespaces",
                    defaultValue = "")
            java.util.List<String> criticalNs,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                    name = "casehub.ops.k8s.approval.production-namespaces",
                    defaultValue = "")
            java.util.List<String> productionNs) {
        this.criticalNamespaces   = criticalNs.isEmpty() || (criticalNs.size() == 1 && criticalNs.getFirst().isEmpty())
                                    ? java.util.Set.of() : java.util.Set.copyOf(criticalNs);
        this.productionNamespaces = productionNs.isEmpty() || (productionNs.size() == 1 && productionNs.getFirst().isEmpty())
                                    ? java.util.Set.of() : java.util.Set.copyOf(productionNs);
    }

    public K8sApprovalEvaluator(java.util.Set<String> criticalNamespaces, java.util.Set<String> productionNamespaces) {
        this.criticalNamespaces   = java.util.Set.copyOf(criticalNamespaces);
        this.productionNamespaces = java.util.Set.copyOf(productionNamespaces);
    }

    K8sApprovalEvaluator() {
        this(java.util.Set.of(), java.util.Set.of());
    }

    @Override
    public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ApprovalDecision.AutoApproved();
        }

        RiskClassification risk = classifyRisk(node.type(), action, wrapper);
        if (!THRESHOLDS.requiresApproval(risk)) {
            return new ApprovalDecision.AutoApproved();
        }

        var plan = new ApprovalPlan(
                node.id(), action, risk,
                generateSummary(wrapper, action),
                tenancyId, node.spec(), null);
        return new ApprovalDecision.RequiresApproval(plan);
    }

    private RiskClassification classifyRisk(NodeType type, StepAction action, InfraDesiredNodeSpec wrapper) {
        RiskClassification base      = classifyByTypeAndAction(type, action);
        String             namespace = extractNamespace(wrapper.resourceSpec());
        if (namespace == null) {return base;}

        if (criticalNamespaces.contains(namespace)) {
            return max(base, RiskClassification.CRITICAL);
        }
        if (productionNamespaces.contains(namespace)) {
            return max(base, RiskClassification.HIGH);
        }
        return base;
    }

    private RiskClassification classifyByTypeAndAction(NodeType type, StepAction action) {
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

    private String extractNamespace(InfraNodeSpec spec) {
        return switch (spec) {
            case K8sNamespaceSpec s -> s.name();
            case K8sDeploymentSpec s -> s.namespace();
            case K8sServiceSpec s -> s.namespace();
            case K8sIngressSpec s -> s.namespace();
            case K8sConfigMapSpec s -> s.namespace();
            default -> null;
        };
    }

    private static RiskClassification max(RiskClassification a, RiskClassification b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private String generateSummary(InfraDesiredNodeSpec wrapper, StepAction action) {
        String verb    = action == StepAction.PROVISION ? "Provision" : "Deprovision";
        String cluster = wrapper.backendId();
        return switch (wrapper.resourceSpec()) {
            case K8sNamespaceSpec s -> verb + " namespace '" + s.name() + "' on " + cluster;
            case K8sDeploymentSpec s -> verb + " deployment '" + s.namespace() + "/" + s.name()
                                        + "' (" + s.image() + ", " + s.replicas() + " replicas) on " + cluster;
            case K8sServiceSpec s -> verb + " service '" + s.namespace() + "/" + s.name()
                                     + "' (port " + s.port() + ") on " + cluster;
            case K8sIngressSpec s -> verb + " ingress '" + s.namespace() + "/" + s.name()
                                     + "' (host: " + s.host() + ") on " + cluster;
            case K8sConfigMapSpec s -> verb + " configmap '" + s.namespace() + "/" + s.name() + "' on " + cluster;
            default -> verb + " " + wrapper.resourceSpec().resourceType() + " on " + cluster;
        };
    }
}
