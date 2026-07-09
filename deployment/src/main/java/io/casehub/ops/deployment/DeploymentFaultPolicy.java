package io.casehub.ops.deployment;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;

import java.util.List;

@ApplicationScoped
public class DeploymentFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actualState) {
        return List.of();
    }
}
