package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceEventSourceTest {

    @Test
    void subscriberReceivesEmittedEvents() {
        var source = new ComplianceEventSource();
        var received = new ArrayList<StateEvent>();

        source.stream()
                .subscribe().with(received::add);

        source.emit(new StateEvent(NodeId.of("ctrl-1"), NodeStatus.DRIFTED, "stale"));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).node()).isEqualTo(NodeId.of("ctrl-1"));
        assertThat(received.get(0).newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void emitBeforeSubscriberIsNotReplayed() {
        var source = new ComplianceEventSource();
        source.emit(new StateEvent(NodeId.of("ctrl-1"), NodeStatus.DRIFTED, "stale"));

        var received = new ArrayList<StateEvent>();
        source.stream()
                .subscribe().with(received::add);

        assertThat(received).isEmpty();
    }
}
