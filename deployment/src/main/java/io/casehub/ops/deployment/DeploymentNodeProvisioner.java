package io.casehub.ops.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.handler.*;

@ApplicationScoped
public class DeploymentNodeProvisioner implements NodeProvisioner {

    private final AgentProvisionHandler agentHandler;
    private final ChannelProvisionHandler channelHandler;
    private final CaseTypeProvisionHandler caseTypeHandler;
    private final TrustPolicyProvisionHandler trustHandler;

    @Inject
    public DeploymentNodeProvisioner(
            AgentProvisionHandler agentHandler,
            ChannelProvisionHandler channelHandler,
            CaseTypeProvisionHandler caseTypeHandler,
            TrustPolicyProvisionHandler trustHandler) {
        this.agentHandler = agentHandler;
        this.channelHandler = channelHandler;
        this.caseTypeHandler = caseTypeHandler;
        this.trustHandler = trustHandler;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof DeploymentNodeSpec spec)) {
            return new ProvisionResult.Failed("spec is not DeploymentNodeSpec");
        }
        return switch (spec) {
            case AgentNodeSpec s -> agentHandler.provision(s, context);
            case ChannelNodeSpec s -> channelHandler.provision(s, context);
            case CaseTypeNodeSpec s -> caseTypeHandler.provision(s, context);
            case TrustPolicyNodeSpec s -> trustHandler.provision(s, context);
        };
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof DeploymentNodeSpec spec)) {
            return new DeprovisionResult.Failed("spec is not DeploymentNodeSpec");
        }
        return switch (spec) {
            case AgentNodeSpec s -> agentHandler.deprovision(s, context);
            case ChannelNodeSpec s -> channelHandler.deprovision(s, context);
            case CaseTypeNodeSpec s -> caseTypeHandler.deprovision(s, context);
            case TrustPolicyNodeSpec s -> trustHandler.deprovision(s, context);
        };
    }
}
