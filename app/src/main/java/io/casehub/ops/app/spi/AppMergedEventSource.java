package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.runtime.DefaultMergedEventSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class AppMergedEventSource extends DefaultMergedEventSource {

    protected AppMergedEventSource() {
        super(java.util.List.of());
    }

    @Inject
    public AppMergedEventSource(Instance<EventSource> sources) {
        super(StreamSupport.stream(sources.spliterator(), false).toList());
    }
}
