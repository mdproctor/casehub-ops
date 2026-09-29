package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerEventSourceTest {

    private ContainerEventSource eventSource;

    @BeforeEach
    void setUp() {
        eventSource = new ContainerEventSource();
    }

    @Test
    void emit_pushes_to_stream() {
        var future = eventSource.stream()
            .select().first(1)
            .collect().asList()
            .subscribeAsCompletionStage();

        var event = new StateEvent(NodeId.of("myapp"), NodeStatus.DRIFTED, "test event");
        eventSource.emit(event);

        assertThat(future).succeedsWithin(Duration.ofSeconds(2))
            .asList().containsExactly(event);
    }

    @Test
    void emitDrift_emits_drifted_status() {
        var future = eventSource.stream()
            .select().first(1)
            .collect().asList()
            .subscribeAsCompletionStage();

        eventSource.emitDrift(NodeId.of("my-container"));

        assertThat(future).succeedsWithin(Duration.ofSeconds(2))
            .asList().singleElement().satisfies(e -> {
                var event = (StateEvent) e;
                assertThat(event.node()).isEqualTo(NodeId.of("my-container"));
                assertThat(event.newStatus()).isEqualTo(NodeStatus.DRIFTED);
                assertThat(event.detail()).isEqualTo("drift detected");
            });
    }

    @Test
    void stream_broadcasts_to_all_subscribers() {
        var sub1 = eventSource.stream()
            .select().first(1)
            .collect().asList()
            .subscribeAsCompletionStage();
        var sub2 = eventSource.stream()
            .select().first(1)
            .collect().asList()
            .subscribeAsCompletionStage();

        var event = new StateEvent(NodeId.of("myapp"), NodeStatus.PRESENT, "started");
        eventSource.emit(event);

        assertThat(sub1).succeedsWithin(Duration.ofSeconds(2))
            .asList().containsExactly(event);
        assertThat(sub2).succeedsWithin(Duration.ofSeconds(2))
            .asList().containsExactly(event);
    }
}
