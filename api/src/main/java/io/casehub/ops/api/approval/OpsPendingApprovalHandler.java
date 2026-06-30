package io.casehub.ops.api.approval;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;

@ApplicationScoped
public class OpsPendingApprovalHandler implements PendingApprovalHandler {

    private final PlanStore planStore;
    private final ConcurrentHashMap<String, PendingEntry> entries = new ConcurrentHashMap<>();

    @Inject
    public OpsPendingApprovalHandler(PlanStore planStore) {
        this.planStore = planStore;
    }

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        var entry = entries.get(key(node.id(), action, tenancyId));
        if (entry == null) {
            return new ApprovalCheckResult.None();
        }
        return switch (entry.status) {
            case PENDING -> new ApprovalCheckResult.Pending(entry.planReference);
            case APPROVED -> new ApprovalCheckResult.Approved(entry.approval);
            case REJECTED -> new ApprovalCheckResult.Rejected(entry.planReference, entry.rejectionReason);
        };
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action, String tenancyId, String planReference) {
        entries.put(key(node.id(), action, tenancyId),
                new PendingEntry(planReference, Status.PENDING, null, null));
        return new StepOutcome.Skipped("pending approval: " + planReference);
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
        var removed = entries.remove(key(node.id(), action, tenancyId));
        if (removed != null) {
            planStore.remove(removed.planReference);
        }
    }

    public boolean approve(NodeId nodeId, StepAction action, String tenancyId, String approvedBy) {
        String k = key(nodeId, action, tenancyId);
        boolean[] transitioned = {false};
        entries.compute(k, (key, entry) -> {
            if (entry == null || entry.status != Status.PENDING) {
                return entry;
            }
            transitioned[0] = true;
            return new PendingEntry(entry.planReference, Status.APPROVED,
                    new PlanApproval(entry.planReference, approvedBy, Instant.now()), null);
        });
        return transitioned[0];
    }

    public boolean reject(NodeId nodeId, StepAction action, String tenancyId, String reason) {
        String k = key(nodeId, action, tenancyId);
        boolean[] transitioned = {false};
        entries.compute(k, (key, entry) -> {
            if (entry == null || entry.status != Status.PENDING) {
                return entry;
            }
            transitioned[0] = true;
            return new PendingEntry(entry.planReference, Status.REJECTED, null, reason);
        });
        return transitioned[0];
    }

    private String key(NodeId nodeId, StepAction action, String tenancyId) {
        return nodeId.value() + ":" + action.name() + ":" + tenancyId;
    }

    private enum Status { PENDING, APPROVED, REJECTED }

    private record PendingEntry(String planReference, Status status, PlanApproval approval, String rejectionReason) {}
}
