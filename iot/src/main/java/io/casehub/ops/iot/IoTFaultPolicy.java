package io.casehub.ops.iot;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.iot.IoTReviewSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class IoTFaultPolicy implements FaultPolicy {

    static final int ESCALATION_THRESHOLD = 3;

    private static final NodeType DEVICE_CONFIG = NodeType.of("device-config");
    private static final NodeType IOT_REVIEW    = NodeType.of("iot-review");

    private final ConcurrentHashMap<NodeId, Integer> faultCounts = new ConcurrentHashMap<>();

    @Override
    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actualState) {
        DesiredNode node = current.nodes().get(event.node());
        if (node != null && IOT_REVIEW.equals(node.type())) {
            return List.of();
        }

        if (event.type() != FaultType.PROVISION_FAILED) {
            return List.of();
        }

        if (node == null || !DEVICE_CONFIG.equals(node.type())) {
            return List.of();
        }

        int count = faultCounts.merge(event.node(), 1, Integer::sum);
        if (count < ESCALATION_THRESHOLD) {
            return List.of();
        }

        NodeId reviewId = NodeId.of("review-" + event.node().value());
        if (current.nodes().containsKey(reviewId)) {
            return List.of();
        }

        return List.of(new GraphMutation.AddNode(
                new DesiredNode(reviewId, IOT_REVIEW,
                                new IoTReviewSpec(event.node(), event.detail()),
                                HumanGating.ALL)));
    }
}
