package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.eidos.api.AgentDescriptorComparator;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentDriftChecker implements NodeDriftChecker {

    private static final Logger LOG = Logger.getLogger(AgentDriftChecker.class);

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

        var actual = agentRegistry.findById(agentSpec.agentId(), tenancyId);
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }

        var desired = agentSpec.toDescriptor(tenancyId);
        var result = AgentDescriptorComparator.compare(desired, actual.get());

        if (!result.matches()) {
            for (var drift : result.drifts()) {
                LOG.debugf("agent %s: %s drifted [%s → %s]",
                        agentSpec.agentId(), drift.field(), drift.desiredValue(), drift.actualValue());
            }
            return NodeStatus.DRIFTED;
        }

        return NodeStatus.PRESENT;
    }
}
