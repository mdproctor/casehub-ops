package io.casehub.ops.api.deployment;

import io.casehub.desiredstate.api.NodeSpec;

public sealed interface DeploymentNodeSpec extends NodeSpec permits
        AgentNodeSpec, ChannelNodeSpec, CaseTypeNodeSpec, TrustPolicyNodeSpec, EndpointNodeSpec {
    String nodeId();
    String nodeType();
}
