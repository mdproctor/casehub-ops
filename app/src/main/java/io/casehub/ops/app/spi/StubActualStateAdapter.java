package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stub ActualStateAdapter that reports all nodes as ABSENT.
 * Yields to real implementations via {@code @DefaultBean}.
 */
@DefaultBean
@ApplicationScoped
public class StubActualStateAdapter implements ActualStateAdapter {

    public Set<NodeType> handledTypes() {
        return Set.of();
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        return new ActualState(desired.nodes().keySet().stream()
                .collect(Collectors.toMap(id -> id, id -> NodeStatus.ABSENT)));
    }
}
