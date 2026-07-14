package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Stub FaultPolicy that ignores all faults.
 * Yields to real implementations via {@code @DefaultBean}.
 */
@DefaultBean
@ApplicationScoped
public class StubFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        return List.of();
    }
}
