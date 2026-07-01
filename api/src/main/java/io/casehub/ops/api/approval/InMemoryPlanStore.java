package io.casehub.ops.api.approval;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InMemoryPlanStore implements PlanStore {

    private final ConcurrentHashMap<String, ApprovalPlan> plans = new ConcurrentHashMap<>();

    @Override
    public String store(ApprovalPlan plan) {
        String ref = UUID.randomUUID().toString();
        plans.put(ref, plan);
        return ref;
    }

    @Override
    public Optional<ApprovalPlan> retrieve(String planReference) {
        return Optional.ofNullable(plans.get(planReference));
    }

    @Override
    public void remove(String planReference) {
        plans.remove(planReference);
    }
}
