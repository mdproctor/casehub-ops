package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Checks drift for agent nodes by comparing registered capabilities against the spec.
 * Extracted from DeploymentActualStateAdapter.checkAgentStatus() and capabilitiesMatch().
 */
@ApplicationScoped
public class AgentDriftChecker implements NodeDriftChecker {
    private final AgentRegistry agentRegistry;

    @Inject
    public AgentDriftChecker(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @Override
    public String nodeType() {
        return "agent";
    }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof AgentNodeSpec agentSpec)) {
            return NodeStatus.UNKNOWN;
        }

        Optional<AgentDescriptor> actual = agentRegistry.findById(agentSpec.agentId(), tenancyId);
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }

        var desired = agentSpec.capabilities().stream().map(c -> c.name()).sorted().toList();
        var existing = actual.get().capabilities().stream().map(c -> c.name()).sorted().toList();
        return desired.equals(existing) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }
}
