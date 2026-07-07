package io.casehub.ops.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;

class InfraFaultPolicyTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final DesiredStateGraphFactory GRAPH_FACTORY = new DefaultDesiredStateGraphFactory();

    private final InfraFaultPolicy policy = new InfraFaultPolicy();

    @Test
    void nodeDestroyedReturnsNoMutation() {
        var event = new FaultEvent(NODE_1, FaultType.NODE_DESTROYED, "instance terminated externally");

        List<GraphMutation> result = policy.onFault(event, GRAPH_FACTORY.empty(), new ActualState(java.util.Map.of()));

        assertThat(result).isEmpty();
    }

    @Test
    void nodeDegradedReturnsNoMutation() {
        var event = new FaultEvent(NODE_1, FaultType.NODE_DEGRADED, "high latency detected");

        List<GraphMutation> result = policy.onFault(event, GRAPH_FACTORY.empty(), new ActualState(java.util.Map.of()));

        assertThat(result).isEmpty();
    }

    @Test
    void provisionFailedReturnsNoMutation() {
        var event = new FaultEvent(NODE_1, FaultType.PROVISION_FAILED, "resource type not supported");

        List<GraphMutation> result = policy.onFault(event, GRAPH_FACTORY.empty(), new ActualState(java.util.Map.of()));

        assertThat(result).isEmpty();
    }

    @Test
    void deprovisionFailedReturnsNoMutation() {
        var event = new FaultEvent(NODE_1, FaultType.DEPROVISION_FAILED, "cleanup failed");

        List<GraphMutation> result = policy.onFault(event, GRAPH_FACTORY.empty(), new ActualState(java.util.Map.of()));

        assertThat(result).isEmpty();
    }

    @Test
    void humanNodeTimeoutReturnsNoMutation() {
        var event = new FaultEvent(NODE_1, FaultType.HUMAN_NODE_TIMEOUT, "approval timed out");

        List<GraphMutation> result = policy.onFault(event, GRAPH_FACTORY.empty(), new ActualState(java.util.Map.of()));

        assertThat(result).isEmpty();
    }

    @Test
    void dependencyUnavailableReturnsNoMutation() {
        var event = new FaultEvent(NODE_1, FaultType.DEPENDENCY_UNAVAILABLE, "upstream node absent");

        List<GraphMutation> result = policy.onFault(event, GRAPH_FACTORY.empty(), new ActualState(java.util.Map.of()));

        assertThat(result).isEmpty();
    }
}
