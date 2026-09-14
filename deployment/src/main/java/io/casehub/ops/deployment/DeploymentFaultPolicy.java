package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.ops.api.deployment.DeploymentReviewSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class DeploymentFaultPolicy implements FaultPolicy {

    private static final NodeType DEPLOYMENT_REVIEW = NodeType.of("deployment-review");

    private final ThresholdFaultPolicy delegate = ThresholdFaultPolicy.builder()
                                                                      .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                                      .nodeTypes(Set.of(
                                                                              NodeType.of("agent"),
                                                                              NodeType.of("channel"),
                                                                              NodeType.of("case_type"),
                                                                              NodeType.of("trust_policy"),
                                                                              NodeType.of("endpoint")))
                                                                      .ignoreTypes(Set.of(DEPLOYMENT_REVIEW))
                                                                      .tier(3, FaultPolicy.addReviewNode(
                                                                              (event, current) -> new DeploymentReviewSpec(event.node(), event.detail())))
                                                                      .build();

    @Override
    public List<GraphMutation<DesiredNode>> onFault(String tenancyId, FaultEvent event,
                                       DesiredStateGraph current, ActualState actualState) {
        return delegate.onFault(tenancyId, event, current, actualState);
    }
}
