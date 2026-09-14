package io.casehub.ops.infra;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.ops.api.infra.InfraReviewSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class InfraFaultPolicy implements FaultPolicy {

    private static final NodeType INFRA_REVIEW = NodeType.of("infra-review");

    private final ThresholdFaultPolicy delegate = ThresholdFaultPolicy.builder()
                                                                      .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                                      .nodeTypes(InfraTypeDiscovery.discoverHandledTypes())
                                                                      .ignoreTypes(Set.of(INFRA_REVIEW))
                                                                      .tier(3, FaultPolicy.addReviewNode(
                                                                              (event, current) -> new InfraReviewSpec(event.node(), event.detail())))
                                                                      .build();

    @Override
    public List<GraphMutation<DesiredNode>> onFault(String tenancyId, FaultEvent event,
                                       DesiredStateGraph current, ActualState actualState) {
        return delegate.onFault(tenancyId, event, current, actualState);
    }
}
