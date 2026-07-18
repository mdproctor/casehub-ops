package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;
import io.casehub.ops.api.approval.PlanStore;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Set;

@ApplicationScoped
public class KubernetesNodeProvisioner implements NodeProvisioner {

    private final K8sHandlerRegistry handlerRegistry;
    private final K8sClientRegistry  clientRegistry;
    private final ApprovalEvaluator  approvalEvaluator;
    private final PlanStore          planStore;

    @Inject
    public KubernetesNodeProvisioner(K8sHandlerRegistry handlerRegistry,
                                     K8sClientRegistry clientRegistry,
                                     ApprovalEvaluator approvalEvaluator,
                                     PlanStore planStore) {
        this.handlerRegistry   = handlerRegistry;
        this.clientRegistry    = clientRegistry;
        this.approvalEvaluator = approvalEvaluator;
        this.planStore         = planStore;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
                ApplicationNodeTypes.K8S_NAMESPACE,
                ApplicationNodeTypes.K8S_DEPLOYMENT,
                ApplicationNodeTypes.K8S_SERVICE,
                ApplicationNodeTypes.K8S_INGRESS,
                ApplicationNodeTypes.K8S_CONFIGMAP);
    }

    @Override
    public Duration resyncInterval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ProvisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }

        if (context.hasApproval()) {
            return handleProvisionReEntry(node, wrapper, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), ref);
        }

        return doProvision(wrapper, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new DeprovisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }

        if (context.hasApproval()) {
            return handleDeprovisionReEntry(node, wrapper, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), ref);
        }

        return doDeprovision(wrapper, context);
    }

    private ProvisionResult handleProvisionReEntry(DesiredNode node, InfraDesiredNodeSpec wrapper,
                                                   ProvisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(wrapper, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(wrapper, context);
        }

        planStore.remove(context.approval().planReference());
        return doProvision(wrapper, context);
    }

    private DeprovisionResult handleDeprovisionReEntry(DesiredNode node, InfraDesiredNodeSpec wrapper,
                                                       DeprovisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(wrapper, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(wrapper, context);
        }

        planStore.remove(context.approval().planReference());
        return doDeprovision(wrapper, context);
    }

    private ProvisionResult doProvision(InfraDesiredNodeSpec wrapper, ProvisionContext context) {
        try {
            String        clusterId    = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            @SuppressWarnings("unchecked")
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            clientRegistry.withRetryOn401(clusterId, client -> {
                handler.apply(client, resourceSpec);
                return null;
            });
            return new ProvisionResult.Success();
        } catch (Exception e) {
            return new ProvisionResult.Failed(e.getMessage());
        }
    }

    private DeprovisionResult doDeprovision(InfraDesiredNodeSpec wrapper, DeprovisionContext context) {
        try {
            String        clusterId    = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            @SuppressWarnings("unchecked")
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            clientRegistry.withRetryOn401(clusterId, client -> {
                handler.delete(client, resourceSpec);
                return null;
            });
            return new DeprovisionResult.Success();
        } catch (Exception e) {
            return new DeprovisionResult.Failed(e.getMessage());
        }
    }
}
