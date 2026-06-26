package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.path.Path;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EndpointDriftChecker implements NodeDriftChecker {

    private final EndpointRegistry endpointRegistry;

    @Inject
    public EndpointDriftChecker(EndpointRegistry endpointRegistry) {
        this.endpointRegistry = endpointRegistry;
    }

    @Override
    public String nodeType() {
        return "endpoint";
    }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof EndpointNodeSpec endpointSpec)) {
            return NodeStatus.UNKNOWN;
        }
        var actual = endpointRegistry.resolve(
                Path.parse(endpointSpec.path()),
                tenancyId);
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }
        var expected = endpointSpec.toDescriptor(tenancyId);
        return expected.equals(actual.get()) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }
}
