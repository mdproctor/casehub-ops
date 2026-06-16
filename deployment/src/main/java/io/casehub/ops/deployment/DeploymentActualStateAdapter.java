package io.casehub.ops.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.handler.CaseTypeProvisionHandler;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class DeploymentActualStateAdapter implements ActualStateAdapter {

    @FunctionalInterface
    interface ChannelLookup {
        Optional<Channel> findByName(String name);
    }

    private final AgentRegistry agentRegistry;
    private final ChannelLookup channelLookup;
    private final CaseTypeProvisionHandler caseTypeHandler;
    private final DeploymentTrustRoutingPolicyProvider trustProvider;
    private final String tenancyId;

    @Inject
    public DeploymentActualStateAdapter(
            AgentRegistry agentRegistry,
            ChannelService channelService,
            CaseTypeProvisionHandler caseTypeHandler,
            DeploymentTrustRoutingPolicyProvider trustProvider) {
        this.agentRegistry = agentRegistry;
        this.channelLookup = channelService::findByName;
        this.caseTypeHandler = caseTypeHandler;
        this.trustProvider = trustProvider;
        this.tenancyId = "default"; // interim until desiredstate#36
    }

    DeploymentActualStateAdapter(
            AgentRegistry agentRegistry,
            ChannelLookup channelLookup,
            CaseTypeProvisionHandler caseTypeHandler,
            DeploymentTrustRoutingPolicyProvider trustProvider,
            String tenancyId) {
        this.agentRegistry = agentRegistry;
        this.channelLookup = channelLookup;
        this.caseTypeHandler = caseTypeHandler;
        this.trustProvider = trustProvider;
        this.tenancyId = tenancyId;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var node : desired.nodes().values()) {
            statuses.put(node.id(), readNodeStatus(node));
        }
        return new ActualState(statuses);
    }

    private NodeStatus readNodeStatus(DesiredNode node) {
        if (!(node.spec() instanceof DeploymentNodeSpec spec)) {
            return NodeStatus.UNKNOWN;
        }

        return switch (spec) {
            case AgentNodeSpec s -> checkAgentStatus(s);
            case ChannelNodeSpec s -> checkChannelStatus(s);
            case CaseTypeNodeSpec s -> NodeStatus.PRESENT;
            case TrustPolicyNodeSpec s -> NodeStatus.PRESENT;
        };
    }

    private NodeStatus checkAgentStatus(AgentNodeSpec spec) {
        Optional<AgentDescriptor> actual = agentRegistry.findById(spec.agentId(), tenancyId);
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }
        return capabilitiesMatch(spec, actual.get()) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }

    private boolean capabilitiesMatch(AgentNodeSpec spec, AgentDescriptor actual) {
        var desired = spec.capabilities().stream().map(c -> c.name()).sorted().toList();
        var existing = actual.capabilities().stream().map(c -> c.name()).sorted().toList();
        return desired.equals(existing);
    }

    private NodeStatus checkChannelStatus(ChannelNodeSpec spec) {
        Optional<Channel> actual = channelLookup.findByName(spec.name());
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }
        return mutableFieldsMatch(spec, actual.get()) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }

    private boolean mutableFieldsMatch(ChannelNodeSpec spec, Channel actual) {
        // Compare mutable fields: allowedTypes, deniedTypes, rateLimitPerChannel, rateLimitPerInstance
        if (!allowedTypesMatch(spec.allowedTypes(), actual.allowedTypes)) {
            return false;
        }
        if (!deniedTypesMatch(spec.deniedTypes(), actual.deniedTypes)) {
            return false;
        }
        if (!Objects.equals(spec.rateLimitPerChannel(), actual.rateLimitPerChannel)) {
            return false;
        }
        return Objects.equals(spec.rateLimitPerInstance(), actual.rateLimitPerInstance);
    }

    private boolean allowedTypesMatch(Set<MessageType> desired, String actualCsv) {
        // Both null means match (both open)
        if (desired == null && actualCsv == null) {
            return true;
        }
        // Empty set is treated as null (both open)
        if ((desired == null || desired.isEmpty()) && actualCsv == null) {
            return true;
        }
        if (desired == null && (actualCsv == null || actualCsv.isEmpty())) {
            return true;
        }
        if (desired == null || actualCsv == null) {
            return false;
        }
        Set<MessageType> actualSet = MessageType.parseTypes(actualCsv);
        return desired.equals(actualSet);
    }

    private boolean deniedTypesMatch(Set<MessageType> desired, String actualCsv) {
        // Both null means match (no restrictions)
        if (desired == null && actualCsv == null) {
            return true;
        }
        // Empty set is treated as null (no restrictions)
        if ((desired == null || desired.isEmpty()) && actualCsv == null) {
            return true;
        }
        if (desired == null && (actualCsv == null || actualCsv.isEmpty())) {
            return true;
        }
        if (desired == null || actualCsv == null) {
            return false;
        }
        Set<MessageType> actualSet = MessageType.parseTypes(actualCsv);
        return desired.equals(actualSet);
    }
}
