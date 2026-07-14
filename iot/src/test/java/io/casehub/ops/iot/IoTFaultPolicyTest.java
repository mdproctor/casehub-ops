package io.casehub.ops.iot;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IoTFaultPolicyTest {

    @Test
    void onFault_returnsEmptyList() {
        var policy = new IoTFaultPolicy();
        var event = new FaultEvent(NodeId.of("dev-1"), FaultType.PROVISION_FAILED, "test");
        var mutations = policy.onFault("tenant-1", event, new DefaultDesiredStateGraphFactory().empty(), new ActualState(java.util.Map.of()));
        assertThat(mutations).isEmpty();
    }
}
