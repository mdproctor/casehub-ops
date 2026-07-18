package io.casehub.ops.app.case_;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ScalingEventCaseDescriptorTest {

    @Test
    void buildReturnsCorrectIdentity() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getNamespace()).isEqualTo("ops");
        assertThat(def.getName()).isEqualTo("scaling-event");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void hasThreeCapabilities() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getCapabilities()).hasSize(3);
        assertThat(def.getCapabilities()).extracting("name")
                .containsExactlyInAnyOrder("evaluate-scaling", "execute-scaling", "verify-convergence");
    }

    @Test
    void hasThreeWorkers() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getWorkers()).hasSize(3);
    }

    @Test
    void hasTwoInternalBindings() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getBindings()).hasSize(2);
        assertThat(def.getBindings()).extracting("name")
                .containsExactlyInAnyOrder("on-scaling-decision", "on-scaling-executed");
    }

    @Test
    void hasCompletionPredicate() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        assertThat(def.getCompletion()).isNotNull();
    }

    @Test
    void decisionBindingTriggersOnScalingDecision() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        var binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-scaling-decision"))
                .findFirst().orElseThrow();
        assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    }

    @Test
    void executedBindingTriggersOnScalingExecuted() {
        CaseDefinition def = ScalingEventCaseDescriptor.build(null, null);
        var binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-scaling-executed"))
                .findFirst().orElseThrow();
        assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateValidScaleUp() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 6,
                "reason", "cpu_threshold_exceeded");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        Map<String, Object> decision = (Map<String, Object>) result.output().get("scalingDecision");
        assertThat(decision).isNotNull();
        assertThat(decision.get("action")).isEqualTo("scale-up");
        assertThat(decision.get("validatedTarget")).isEqualTo(6);
        assertThat(decision.get("serviceId")).isEqualTo("order-processor");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateValidScaleDown() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 6,
                "targetReplicas", 3,
                "reason", "low_utilization");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        Map<String, Object> decision = (Map<String, Object>) result.output().get("scalingDecision");
        assertThat(decision).isNotNull();
        assertThat(decision.get("action")).isEqualTo("scale-down");
        assertThat(decision.get("validatedTarget")).isEqualTo(3);
    }

    @Test
    void evaluateNoOpSameReplicas() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 3,
                "reason", "periodic_check");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.output()).containsEntry("scalingStatus", "no-change-needed");
        assertThat(result.output()).doesNotContainKey("scalingDecision");
    }

    @Test
    void evaluateRejectsZeroTarget() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 0,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    void evaluateRejectsNegativeTarget() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", -1,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    void evaluateRejectsBlankServiceId() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "  ",
                "currentReplicas", 3,
                "targetReplicas", 6,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    void evaluateRejectsMissingServiceId() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "currentReplicas", 3,
                "targetReplicas", 6,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    void evaluateNullInputReturnsFailed() {
        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(null);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateClampsTooHighTarget() {
        var input = new java.util.HashMap<String, Object>();
        input.put("applicationId", UUID.randomUUID().toString());
        input.put("tenancyId", "tenant-1");
        input.put("serviceId", "order-processor");
        input.put("currentReplicas", 3);
        input.put("targetReplicas", 100);
        input.put("maxReplicas", 10);
        input.put("minReplicas", 1);

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        Map<String, Object> decision = (Map<String, Object>) result.output().get("scalingDecision");
        assertThat(decision.get("validatedTarget")).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateClampsTooLowTarget() {
        var input = new java.util.HashMap<String, Object>();
        input.put("applicationId", UUID.randomUUID().toString());
        input.put("tenancyId", "tenant-1");
        input.put("serviceId", "order-processor");
        input.put("currentReplicas", 5);
        input.put("targetReplicas", 1);
        input.put("minReplicas", 3);
        input.put("maxReplicas", 20);

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        Map<String, Object> decision = (Map<String, Object>) result.output().get("scalingDecision");
        assertThat(decision.get("validatedTarget")).isEqualTo(3);
    }

    @Test
    void evaluateNoChangeAfterClamping() {
        var input = new java.util.HashMap<String, Object>();
        input.put("applicationId", UUID.randomUUID().toString());
        input.put("tenancyId", "tenant-1");
        input.put("serviceId", "order-processor");
        input.put("currentReplicas", 5);
        input.put("targetReplicas", 10);
        input.put("maxReplicas", 5);
        input.put("minReplicas", 1);

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.output()).containsEntry("scalingStatus", "no-change-needed");
    }

    @Test
    void scalingRequiredBindingUsesUnifiedPath() {
        var def = ApplicationCaseDescriptor.build();
        var binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-scaling-required"))
                .findFirst().orElseThrow();
        var subCaseTarget = (io.casehub.api.model.SubCaseTarget) binding.target();
        var inputMapping = (io.casehub.api.model.SubCaseMapping.Expression) subCaseTarget.subCase().inputMapping();
        assertThat(inputMapping.expression()).isEqualTo(".scalingRequired");
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluateWithoutPolicyFieldsUsesDefaults() {
        var input = Map.<String, Object>of(
                "applicationId", UUID.randomUUID().toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "currentReplicas", 3,
                "targetReplicas", 6,
                "reason", "test");

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        Map<String, Object> decision = (Map<String, Object>) result.output().get("scalingDecision");
        assertThat(decision.get("validatedTarget")).isEqualTo(6);
    }

    @Test
    void evaluateAfterCooldownAllowsScaling() {
        var input = new java.util.HashMap<String, Object>();
        input.put("applicationId", UUID.randomUUID().toString());
        input.put("tenancyId", "tenant-1");
        input.put("serviceId", "order-processor");
        input.put("currentReplicas", 3);
        input.put("targetReplicas", 6);
        input.put("cooldownSeconds", 60);
        input.put("lastScalingTimestamp", java.time.Instant.now().minus(java.time.Duration.ofSeconds(120)).toString());

        WorkerResult result = ScalingEventCaseDescriptor.evaluateScaling(input);

        assertThat(result.output()).containsKey("scalingDecision");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeOutputsAuditTrailWithNodeIds() {
        java.util.Set<String> returnedNodeIds = java.util.Set.of("cluster-1:order-processor:deployment");
        io.casehub.ops.app.service.ApplicationLifecycleService mockService =
                new io.casehub.ops.app.service.ApplicationLifecycleService() {
                    @Override
                    public java.util.Set<String> updateServiceReplicas(UUID appId, String serviceId,
                                                                       int newReplicas, String tenancyId) {
                        return returnedNodeIds;
                    }
                };

        UUID appId = UUID.randomUUID();
        var decision = Map.<String, Object>of(
                "action", "scale-up",
                "applicationId", appId.toString(),
                "tenancyId", "tenant-1",
                "serviceId", "order-processor",
                "validatedTarget", 6,
                "currentReplicas", 3);
        var input = Map.<String, Object>of("scalingDecision", decision);

        WorkerResult result = ScalingEventCaseDescriptor.executeScaling(input, mockService);

        Map<String, Object> executed = (Map<String, Object>) result.output().get("scalingExecuted");
        assertThat(executed).isNotNull();
        assertThat(executed.get("serviceId")).isEqualTo("order-processor");
        assertThat(executed.get("previousReplicas")).isEqualTo(3);
        assertThat(executed.get("newReplicas")).isEqualTo(6);
        assertThat((java.util.List<String>) executed.get("affectedNodeIds"))
                .containsExactlyInAnyOrderElementsOf(returnedNodeIds);
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifyRegistersWithConvergenceTracker() {
        io.casehub.ops.app.service.NodeConvergenceTracker tracker =
                new io.casehub.ops.app.service.NodeConvergenceTracker(
                        (io.casehub.ops.app.service.NodeConvergenceTracker.ConvergenceSignaler) (caseId, path, value) -> {},
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        UUID caseId = UUID.randomUUID();
        io.casehub.api.model.WorkerExecutionContext.set(
                new io.casehub.api.model.WorkerContext("test", caseId,
                        java.util.List.of(), java.util.List.of(), null, Map.of()));
        try {
            var input = Map.<String, Object>of(
                    "scalingExecuted", Map.of(
                            "serviceId", "order-processor",
                            "previousReplicas", 3,
                            "newReplicas", 6,
                            "affectedNodeIds", java.util.List.of("cluster-1:order-processor:deployment")));

            WorkerResult result = ScalingEventCaseDescriptor.verifyConvergence(input, tracker);

            assertThat(result.output()).isEmpty();
            assertThat(tracker.isTracking(caseId)).isTrue();
        } finally {
            io.casehub.api.model.WorkerExecutionContext.clear();
        }
    }
}
