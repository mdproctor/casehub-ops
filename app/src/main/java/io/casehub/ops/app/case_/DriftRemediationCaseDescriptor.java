package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;

import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.ops.app.service.NodeConvergenceTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DriftRemediationCaseDescriptor {

    private static final List<String> SECURITY_FIELDS =
            List.of("image", "serviceAccount", "rbac", "secrets");

    private DriftRemediationCaseDescriptor() {}

    public static CaseDefinition build(NodeConvergenceTracker tracker) {
        return CaseDefinition.builder()
                             .namespace("ops")
                             .name("drift-remediation")
                             .version("1.0")
                             .title("Drift Remediation")
                             .summary("Classifies, remediates, and optionally escalates detected drift")
                             .capabilities(capabilities())
                             .workers(workers(tracker))
                             .bindings(bindings())
                             .completion(".remediationStatus == \"converged\"")
                             .build();
    }

    private static List<Capability> capabilities() {
        return List.of(
                Capability.of("classify-drift", "any", "any"),
                Capability.of("remediate-drift", "any", "any"),
                Capability.of("escalate-drift", "any", "any"));
    }

    @SuppressWarnings("unchecked")
    private static List<Worker> workers(NodeConvergenceTracker tracker) {
        return List.of(
                Worker.builder()
                      .name("drift-classify-worker")
                      .capabilityName("classify-drift")
                      .function(new WorkerFunction.Sync<>(Map.class,
                                                          DriftRemediationCaseDescriptor::classifyDrift))
                      .build(),
                Worker.builder()
                      .name("drift-remediate-worker")
                      .capabilityName("remediate-drift")
                      .function(new WorkerFunction.Sync<>(Map.class,
                                                          input -> remediateDrift(input, tracker)))
                      .build(),
                Worker.builder()
                      .name("drift-escalate-worker")
                      .capabilityName("escalate-drift")
                      .function(new WorkerFunction.Sync<>(Map.class,
                                                          DriftRemediationCaseDescriptor::escalateDrift))
                      .build());
    }

    private static List<Binding> bindings() {
        return List.of(
                Binding.builder()
                       .name("on-classification-complete")
                       .on(new ContextChangeTrigger(".driftClassification"))
                       .capability(Capability.of("remediate-drift", "any", "any"))
                       .build(),
                Binding.builder()
                       .name("on-escalation-required")
                       .on(new ContextChangeTrigger(".escalationRequired"))
                       .capability(Capability.of("escalate-drift", "any", "any"))
                       .build());
    }

    static WorkerResult classifyDrift(Map<String, Object> input) {
        int consecutiveDriftCount = input.containsKey("consecutiveDriftCount")
                                    ? ((Number) input.get("consecutiveDriftCount")).intValue() : 1;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftDetails =
                (List<Map<String, Object>>) input.getOrDefault("driftDetails", List.of());

        boolean persistent = consecutiveDriftCount > 1;
        boolean multiNode  = driftDetails.size() > 1;
        boolean securitySensitive = driftDetails.stream()
                                                .flatMap(nd -> {
                                                    @SuppressWarnings("unchecked")
                                                    List<Map<String, Object>> fields =
                                                            (List<Map<String, Object>>) nd.getOrDefault("fields", List.of());
                                                    return fields.stream();
                                                })
                                                .anyMatch(f -> SECURITY_FIELDS.contains(f.get("fieldName")));

        boolean critical = persistent || securitySensitive || multiNode;
        String  severity = critical ? "critical" : "benign";

        var reasons = new ArrayList<String>();
        if (persistent) {reasons.add("persistent drift (consecutive count: " + consecutiveDriftCount + ")");}
        if (securitySensitive) {reasons.add("security-sensitive fields changed");}
        if (multiNode) {reasons.add("multiple nodes drifted (" + driftDetails.size() + ")");}
        if (reasons.isEmpty()) {reasons.add("single-node, first occurrence, non-security fields");}

        List<String> nodeIds = driftDetails.stream()
                                           .map(nd -> (String) nd.get("nodeId"))
                                           .toList();

        var classification = new LinkedHashMap<String, Object>();
        classification.put("severity", severity);
        classification.put("reason", String.join("; ", reasons));
        classification.put("nodeIds", nodeIds);

        return WorkerResult.of(Map.of("driftClassification", classification));
    }

    @SuppressWarnings("unchecked")
    static WorkerResult remediateDrift(Map<String, Object> input, NodeConvergenceTracker tracker) {
        Map<String, Object> classification =
                (Map<String, Object>) input.get("driftClassification");
        String severity = classification != null
                          ? (String) classification.get("severity") : "benign";

        if ("critical".equals(severity)) {
            return WorkerResult.of(Map.of("escalationRequired", true));
        }

        List<String> nodeIds = classification != null
                               ? (List<String>) classification.getOrDefault("nodeIds", List.of())
                               : List.of();
        if (!nodeIds.isEmpty()) {
            UUID caseId = WorkerExecutionContext.current().caseId();
            tracker.register(caseId, new HashSet<>(nodeIds), "remediationStatus",
                             "converged");
        }
        return WorkerResult.of(Map.of("remediationStatus", "auto-remediating"));
    }

    @SuppressWarnings("unchecked")
    static WorkerResult escalateDrift(Map<String, Object> input) {
        Map<String, Object> classification =
                (Map<String, Object>) input.getOrDefault("driftClassification", Map.of());
        List<String> nodeIds =
                (List<String>) classification.getOrDefault("nodeIds", List.of());
        String reason = (String) classification.getOrDefault("reason", "unknown");

        return WorkerResult.of(Map.of(
                "escalation", Map.of(
                        "summary", "Persistent drift detected on " + nodeIds.size() + " node(s)",
                        "detail", reason,
                        "nodeIds", nodeIds,
                        "risk", "HIGH")));
    }
}
