package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GraphMutation;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class ComplianceFaultPolicy implements FaultPolicy {
    @Override
    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actualState) {
        return List.of();
    }
}
