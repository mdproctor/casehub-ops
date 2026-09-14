package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;

import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.ops.api.k8s.K8sReviewSpec;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class KubernetesFaultPolicy implements FaultPolicy {

    private static final NodeType K8S_REVIEW = NodeType.of("k8s-review");

    private final ThresholdFaultPolicy delegate = ThresholdFaultPolicy.builder()
                                                                      .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                                      .nodeTypes(Set.of(
                                                                              ApplicationNodeTypes.K8S_NAMESPACE,
                                                                              ApplicationNodeTypes.K8S_DEPLOYMENT,
                                                                              ApplicationNodeTypes.K8S_SERVICE,
                                                                              ApplicationNodeTypes.K8S_INGRESS,
                                                                              ApplicationNodeTypes.K8S_CONFIGMAP))
                                                                      .ignoreTypes(Set.of(K8S_REVIEW))
                                                                      .tier(3, FaultPolicy.addReviewNode(
                                                                              (event, current) -> new K8sReviewSpec(event.node(), event.detail())))
                                                                      .build();

    @Override
    public List<GraphMutation<DesiredNode>> onFault(String tenancyId, FaultEvent event,
                                       DesiredStateGraph current, ActualState actual) {
        return delegate.onFault(tenancyId, event, current, actual);
    }
}
