package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.CaseTypeNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import io.casehub.ops.deployment.handler.CaseTypeProvisionHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Checks drift for case type nodes by querying CaseTypeProvisionHandler.isRegistered().
 * Case types are either PRESENT or ABSENT — no drift detection on their content.
 */
@ApplicationScoped
public class CaseTypeDriftChecker implements NodeDriftChecker {
    private final CaseTypeProvisionHandler handler;

    @Inject
    public CaseTypeDriftChecker(CaseTypeProvisionHandler handler) {
        this.handler = handler;
    }

    @Override
    public String nodeType() {
        return "case_type";
    }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof CaseTypeNodeSpec cts)) {
            return NodeStatus.UNKNOWN;
        }
        return handler.isRegistered(cts.nodeId()) ? NodeStatus.PRESENT : NodeStatus.ABSENT;
    }
}
