package io.casehub.ops.api.approval;

import java.util.Optional;

public interface PlanStore {
    String store(ApprovalPlan plan);
    Optional<ApprovalPlan> retrieve(String planReference);
    void remove(String planReference);
}
