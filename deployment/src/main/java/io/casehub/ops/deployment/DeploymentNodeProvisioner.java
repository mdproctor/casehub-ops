package io.casehub.ops.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.handler.*;

import java.util.Set;

@ApplicationScoped
public class DeploymentNodeProvisioner implements NodeProvisioner {

    private final AgentProvisionHandler agentHandler;
    private final ChannelProvisionHandler channelHandler;
    private final CaseTypeProvisionHandler caseTypeHandler;
    private final TrustPolicyProvisionHandler trustHandler;
    private final EndpointProvisionHandler endpointHandler;
    private final SpecHashStore specHashStore;
    private final ApprovalEvaluator approvalEvaluator;
    private final PlanStore planStore;

    @Inject
    public DeploymentNodeProvisioner(
            AgentRegistry agentRegistry,
            DeploymentProviderConfigStore providerConfigStore,
            ChannelProvisionHandler channelHandler,
            CaseTypeProvisionHandler caseTypeHandler,
            TrustPolicyProvisionHandler trustHandler,
            EndpointProvisionHandler endpointHandler,
            SpecHashStore specHashStore,
            ApprovalEvaluator approvalEvaluator,
            PlanStore planStore) {
        this.agentHandler = new AgentProvisionHandler(agentRegistry, providerConfigStore);
        this.channelHandler = channelHandler;
        this.caseTypeHandler = caseTypeHandler;
        this.trustHandler = trustHandler;
        this.endpointHandler = endpointHandler;
        this.specHashStore = specHashStore;
        this.approvalEvaluator = approvalEvaluator;
        this.planStore = planStore;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
                NodeType.of("agent"),
                NodeType.of("channel"),
                NodeType.of("case_type"),
                NodeType.of("trust_policy"),
                NodeType.of("endpoint"));
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof DeploymentNodeSpec spec)) {
            return new ProvisionResult.Failed("spec is not DeploymentNodeSpec");
        }

        if (context.hasApproval()) {
            return handleProvisionReEntry(node, spec, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), ref);
        }

        return doProvision(node, spec, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof DeploymentNodeSpec spec)) {
            return new DeprovisionResult.Failed("spec is not DeploymentNodeSpec");
        }

        if (context.hasApproval()) {
            return handleDeprovisionReEntry(node, spec, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), ref);
        }

        return doDeprovision(node, spec, context);
    }

    private ProvisionResult handleProvisionReEntry(DesiredNode node, DeploymentNodeSpec spec, ProvisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            // Plan expired or was removed — re-evaluate without approval
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node, spec, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            // Spec changed since approval — remove stale plan, re-evaluate
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node, spec, context);
        }

        // Plan valid — execute and clean up
        ProvisionResult result = doProvision(node, spec, context);
        if (result instanceof ProvisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private DeprovisionResult handleDeprovisionReEntry(DesiredNode node, DeploymentNodeSpec spec, DeprovisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node, spec, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node, spec, context);
        }

        DeprovisionResult result = doDeprovision(node, spec, context);
        if (result instanceof DeprovisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private ProvisionResult doProvision(DesiredNode node, DeploymentNodeSpec spec, ProvisionContext context) {
        ProvisionResult result = switch (spec) {
            case AgentNodeSpec s -> agentHandler.provision(s, context);
            case ChannelNodeSpec s -> channelHandler.provision(s, context);
            case CaseTypeNodeSpec s -> caseTypeHandler.provision(s, context);
            case TrustPolicyNodeSpec s -> trustHandler.provision(s, context);
            case EndpointNodeSpec s -> endpointHandler.provision(s, context);
        };
        if (result instanceof ProvisionResult.Success) {
            specHashStore.record(node.id(), node.spec());
        }
        return result;
    }

    private DeprovisionResult doDeprovision(DesiredNode node, DeploymentNodeSpec spec, DeprovisionContext context) {
        DeprovisionResult result = switch (spec) {
            case AgentNodeSpec s -> agentHandler.deprovision(s, context);
            case ChannelNodeSpec s -> channelHandler.deprovision(s, context);
            case CaseTypeNodeSpec s -> caseTypeHandler.deprovision(s, context);
            case TrustPolicyNodeSpec s -> trustHandler.deprovision(s, context);
            case EndpointNodeSpec s -> endpointHandler.deprovision(s, context);
        };
        if (result instanceof DeprovisionResult.Success) {
            specHashStore.remove(node.id());
        }
        return result;
    }
}
