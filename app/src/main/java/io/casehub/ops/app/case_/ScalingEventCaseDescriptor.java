package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.ops.app.service.ApplicationLifecycleService;
import io.casehub.ops.app.service.NodeConvergenceTracker;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ScalingEventCaseDescriptor {

    private ScalingEventCaseDescriptor() {}

    public static CaseDefinition build(ApplicationLifecycleService lifecycleService,
                                        NodeConvergenceTracker convergenceTracker) {
        return CaseDefinition.builder()
                .namespace("ops")
                .name("scaling-event")
                .version("1.0")
                .title("Scaling Event")
                .summary("Evaluates, executes, and verifies a scaling request against the desired-state graph")
                .capabilities(capabilities())
                .workers(workers(lifecycleService, convergenceTracker))
                .bindings(bindings())
                .completion(".scalingStatus == \"converged\" || .scalingStatus == \"no-change-needed\"")
                .build();
    }

    private static List<Capability> capabilities() {
        return List.of(
                Capability.of("evaluate-scaling", "any", "any"),
                Capability.of("execute-scaling", "any", "any"),
                Capability.of("verify-convergence", "any", "any"));
    }

    @SuppressWarnings("unchecked")
    private static List<Worker> workers(ApplicationLifecycleService lifecycleService,
                                         NodeConvergenceTracker convergenceTracker) {
        return List.of(
                Worker.builder()
                        .name("evaluate-scaling-worker")
                        .capabilityName("evaluate-scaling")
                        .function(new WorkerFunction.Sync<>(Map.class,
                                ScalingEventCaseDescriptor::evaluateScaling))
                        .build(),
                Worker.builder()
                        .name("execute-scaling-worker")
                        .capabilityName("execute-scaling")
                        .function(new WorkerFunction.Sync<>(Map.class,
                                input -> executeScaling(input, lifecycleService)))
                        .build(),
                Worker.builder()
                        .name("verify-convergence-worker")
                        .capabilityName("verify-convergence")
                        .function(new WorkerFunction.Sync<>(Map.class,
                                input -> verifyConvergence(input, convergenceTracker)))
                        .build());
    }

    private static List<Binding> bindings() {
        return List.of(
                Binding.builder()
                        .name("on-scaling-decision")
                        .on(new ContextChangeTrigger(".scalingDecision"))
                        .capability(Capability.of("execute-scaling", "any", "any"))
                        .build(),
                Binding.builder()
                        .name("on-scaling-executed")
                        .on(new ContextChangeTrigger(".scalingExecuted"))
                        .capability(Capability.of("verify-convergence", "any", "any"))
                        .build());
    }

    static WorkerResult evaluateScaling(Map<String, Object> input) {
        if (input == null) {
            return WorkerResult.failed("Scaling spec is null — .scalingRequired missing from parent blackboard");
        }

        String serviceId = (String) input.get("serviceId");
        if (serviceId == null || serviceId.isBlank()) {
            return WorkerResult.failed("serviceId is required");
        }

        Number targetReplicasNum  = (Number) input.get("targetReplicas");
        Number currentReplicasNum = (Number) input.get("currentReplicas");
        if (targetReplicasNum == null) {
            return WorkerResult.failed("targetReplicas is required");
        }
        int targetReplicas  = targetReplicasNum.intValue();
        int currentReplicas = currentReplicasNum != null ? currentReplicasNum.intValue() : 0;

        if (targetReplicas <= 0) {
            return WorkerResult.failed("targetReplicas must be > 0, got: " + targetReplicas);
        }

        ScalingPolicy policy = buildPolicy(input);

        targetReplicas = policy.clamp(targetReplicas);

        if (targetReplicas == currentReplicas) {
            return WorkerResult.of(Map.of("scalingStatus", "no-change-needed"));
        }

        String action        = targetReplicas > currentReplicas ? "scale-up" : "scale-down";
        String applicationId = (String) input.get("applicationId");
        String tenancyId     = (String) input.get("tenancyId");
        String reason        = (String) input.getOrDefault("reason", "unspecified");

        var decision = new LinkedHashMap<String, Object>();
        decision.put("action", action);
        decision.put("applicationId", applicationId);
        decision.put("tenancyId", tenancyId);
        decision.put("serviceId", serviceId);
        decision.put("validatedTarget", targetReplicas);
        decision.put("currentReplicas", currentReplicas);
        decision.put("reason", reason);

        return WorkerResult.of(Map.of("scalingDecision", decision));}

    private static ScalingPolicy buildPolicy(Map<String, Object> input) {
        Number minNum      = (Number) input.get("minReplicas");
        Number maxNum      = (Number) input.get("maxReplicas");
        Number cooldownNum = (Number) input.get("cooldownSeconds");

        if (minNum == null && maxNum == null && cooldownNum == null) {
            return ScalingPolicy.UNBOUNDED;
        }

        int min = minNum != null ? minNum.intValue() : 0;
        int max = maxNum != null ? maxNum.intValue() : Integer.MAX_VALUE;
        java.time.Duration cooldown = cooldownNum != null
                                      ? java.time.Duration.ofSeconds(cooldownNum.longValue()) : null;
        return new ScalingPolicy(min, max, cooldown);
    }


    @SuppressWarnings("unchecked")
    static WorkerResult executeScaling(Map<String, Object> input,
                                        ApplicationLifecycleService lifecycleService) {
        Map<String, Object> decision = (Map<String, Object>) input.get("scalingDecision");
        String applicationId = (String) decision.get("applicationId");
        String tenancyId = (String) decision.get("tenancyId");
        String serviceId = (String) decision.get("serviceId");
        int newReplicas = ((Number) decision.get("validatedTarget")).intValue();
        int previousReplicas = ((Number) decision.get("currentReplicas")).intValue();

        Set<String> affectedNodeIds = lifecycleService.updateServiceReplicas(
                UUID.fromString(applicationId), serviceId, newReplicas, tenancyId);

        var executed = new LinkedHashMap<String, Object>();
        executed.put("serviceId", serviceId);
        executed.put("previousReplicas", previousReplicas);
        executed.put("newReplicas", newReplicas);
        executed.put("affectedNodeIds", List.copyOf(affectedNodeIds));

        return WorkerResult.of(Map.of("scalingExecuted", executed));
    }

    @SuppressWarnings("unchecked")
    static WorkerResult verifyConvergence(Map<String, Object> input,
                                           NodeConvergenceTracker convergenceTracker) {
        Map<String, Object> executed = (Map<String, Object>) input.get("scalingExecuted");
        List<String> affectedNodeIdsList = (List<String>) executed.get("affectedNodeIds");
        Set<String> affectedNodeIds = new HashSet<>(affectedNodeIdsList);

        UUID caseId = WorkerExecutionContext.current().caseId();
        convergenceTracker.register(caseId, affectedNodeIds, "scalingStatus",
                "converged");

        return WorkerResult.of(Map.of());
    }
}
