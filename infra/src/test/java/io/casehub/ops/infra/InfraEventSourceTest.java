package io.casehub.ops.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;

class InfraEventSourceTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");

    @Test
    void emittedEventsAppearInStream() {
        var eventSource = new InfraEventSource();
        var event = new StateEvent(NODE_1, NodeStatus.DRIFTED, "drift detected");

        var subscriber = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        eventSource.emit(event);

        subscriber.assertItems(event);
    }

    @Test
    void emitDriftCreatesCorrectEvent() {
        var eventSource = new InfraEventSource();

        var subscriber = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        eventSource.emitDrift(NODE_1);

        var items = subscriber.getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).node()).isEqualTo(NODE_1);
        assertThat(items.get(0).newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void multipleSubscribersReceiveEvents() {
        var eventSource = new InfraEventSource();
        var event = new StateEvent(NODE_1, NodeStatus.PRESENT, "state changed");

        var subscriber1 = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));
        var subscriber2 = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        eventSource.emit(event);

        subscriber1.assertItems(event);
        subscriber2.assertItems(event);
    }

    @Test
    void streamIsHot() {
        var eventSource = new InfraEventSource();
        var earlyEvent = new StateEvent(NODE_1, NodeStatus.DRIFTED, "drift detected");

        // Emit before anyone subscribes
        eventSource.emit(earlyEvent);

        // Subscribe after the event was emitted
        var subscriber = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        // The early event should NOT be received (hot stream)
        subscriber.assertHasNotReceivedAnyItem();
    }
}
