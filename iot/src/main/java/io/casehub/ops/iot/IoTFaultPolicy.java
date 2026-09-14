package io.casehub.ops.iot;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;

import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.ops.api.iot.IoTReviewSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class IoTFaultPolicy implements FaultPolicy {

    static final int ESCALATION_THRESHOLD = 3;

    private static final NodeType DEVICE_CONFIG = NodeType.of("device-config");
    private static final NodeType IOT_REVIEW    = NodeType.of("iot-review");

    private final ThresholdFaultPolicy delegate = ThresholdFaultPolicy.builder()
                                                                      .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                                      .nodeTypes(Set.of(DEVICE_CONFIG))
                                                                      .ignoreTypes(Set.of(IOT_REVIEW))
                                                                      .tier(ESCALATION_THRESHOLD, FaultPolicy.addReviewNode(
                                                                              (event, current) -> new IoTReviewSpec(event.node(), event.detail())))
                                                                      .build();

    @Override
    public List<GraphMutation<DesiredNode>> onFault(String tenancyId, FaultEvent event,
                                       DesiredStateGraph current, ActualState actual) {
        return delegate.onFault(tenancyId, event, current, actual);
    }
}
