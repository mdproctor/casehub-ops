package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.runtime.DefaultActualStateAdapterRouter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.stream.StreamSupport;

@ApplicationScoped
public class AppActualStateAdapterRouter extends DefaultActualStateAdapterRouter {

    protected AppActualStateAdapterRouter() {
        super(java.util.List.of());
    }

    @Inject
    public AppActualStateAdapterRouter(Instance<ActualStateAdapter> adapters) {
        super(StreamSupport.stream(adapters.spliterator(), false).toList());
    }
}
