package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.StateEvent;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stub EventSource backed by a BroadcastProcessor.
 * Yields to real implementations via {@code @DefaultBean}.
 */
@DefaultBean
@ApplicationScoped
public class StubEventSource implements EventSource {

    private final BroadcastProcessor<StateEvent> emitter = BroadcastProcessor.create();

    @Override
    public Multi<StateEvent> stream() {
        return emitter;
    }

    public void emit(StateEvent event) {
        emitter.onNext(event);
    }
}
