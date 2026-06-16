package io.casehub.ops.deployment;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;

/**
 * Hot event stream for the deployment domain.
 *
 * <p>Provides a {@link Multi} of {@link StateEvent} that handlers and tests can push
 * events into via {@link #emit(StateEvent)} or the convenience {@link #emitDrift(NodeId)}.
 *
 * <p>The stream is hot: events emitted before a subscriber connects are not replayed.
 * Multiple subscribers each receive every event emitted after they subscribe.
 *
 * <p>Periodic polling is deliberately absent — that is a runtime concern
 * (casehub-desiredstate#19). This event source only handles manually pushed events.
 */
@ApplicationScoped
public class DeploymentEventSource implements EventSource {

    private final Multi<StateEvent> stream;
    private volatile MultiEmitter<? super StateEvent> emitter;

    public DeploymentEventSource() {
        this.stream = Multi.createFrom()
                .<StateEvent>emitter(e -> this.emitter = e, BackPressureStrategy.BUFFER)
                .broadcast().toAllSubscribers();
    }

    @Override
    public Multi<StateEvent> stream() {
        return stream;
    }

    /**
     * Emit a state event into the stream.
     *
     * @param event the event to broadcast to all subscribers
     */
    public void emit(StateEvent event) {
        var e = this.emitter;
        if (e != null) {
            e.emit(event);
        }
    }

    /**
     * Convenience: emit a drift-detected event for the given node.
     *
     * @param nodeId the node that drifted
     */
    public void emitDrift(NodeId nodeId) {
        emit(new StateEvent(nodeId, NodeStatus.DRIFTED, "drift detected"));
    }
}
