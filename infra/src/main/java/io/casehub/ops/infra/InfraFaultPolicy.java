package io.casehub.ops.infra;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Default fault rules for the infrastructure domain.
 *
 * <p>FaultPolicy only answers one question: should we mutate the desired-state graph?
 * Everything else — retry, re-provision, human escalation, WorkItem creation — is a
 * runtime concern handled by the ReconciliationLoop.
 *
 * <p>For the PoC, all fault types return an empty list — no graph mutations. The runtime
 * handles retry, re-provisioning, and escalation. Node removal after retry exhaustion
 * is a ReconciliationLoop concern, not a fault policy decision on first failure.
 * The runtime's {@link FaultType} has no transient/permanent distinction, so the
 * policy cannot safely distinguish retriable failures from permanent ones.
 */
@ApplicationScoped
public class InfraFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actualState) {
        return List.of();
    }
}
