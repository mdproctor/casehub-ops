package io.casehub.ops.api.deployment;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;

public interface NodeDriftChecker {
    NodeStatus check(NodeSpec spec, String tenancyId);
    String nodeType();
}
