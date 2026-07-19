package io.casehub.ops.app.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.PlanStoreMapper;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JpaPlanStoreTest {

    private final ObjectMapper mapper = PlanStoreMapper.mapper();

    @Test
    void infraSpecRoundTrip() throws Exception {
        var spec = new InfraDesiredNodeSpec(new K8sNamespaceSpec("production", Labels.empty()), "cluster-1");
        var plan = new ApprovalPlan(
                NodeId.of("ns-prod"), StepAction.DEPROVISION, RiskClassification.CRITICAL,
                "Deprovision namespace 'production' on cluster-1",
                "tenant-1", spec, null);

        String       json         = mapper.writeValueAsString(plan);
        ApprovalPlan deserialized = mapper.readValue(json, ApprovalPlan.class);

        assertThat(deserialized).isEqualTo(plan);
        assertThat(deserialized.originalSpec()).isEqualTo(spec);
        assertThat(deserialized.originalSpec()).isInstanceOf(InfraDesiredNodeSpec.class);
    }

    @Test
    void deploymentSpecRoundTrip() throws Exception {
        var spec = new AgentNodeSpec("agent-1", "test-agent", "reviewer",
                                     "claude", "opus", null, null, null, null, null,
                                     null, null, null, null, null, null, null, null);
        var plan = new ApprovalPlan(
                NodeId.of("agent-1"), StepAction.PROVISION, RiskClassification.HIGH,
                "Provision agent 'agent-1'",
                "tenant-1", spec, null);

        String       json         = mapper.writeValueAsString(plan);
        ApprovalPlan deserialized = mapper.readValue(json, ApprovalPlan.class);

        assertThat(deserialized).isEqualTo(plan);
        assertThat(deserialized.originalSpec()).isInstanceOf(AgentNodeSpec.class);
    }

    @Test
    void k8sDeploymentSpecRoundTrip() throws Exception {
        var resources = new ResourceRequirements("100m", "500m", "128Mi", "256Mi");
        var spec = new InfraDesiredNodeSpec(
                new K8sDeploymentSpec("default", "my-app", "nginx:latest", 3, resources, Labels.empty()),
                "cluster-2");
        var plan = new ApprovalPlan(
                NodeId.of("deploy-1"), StepAction.PROVISION, RiskClassification.MEDIUM,
                "Provision deployment 'default/my-app'",
                "tenant-2", spec, null);

        String       json         = mapper.writeValueAsString(plan);
        ApprovalPlan deserialized = mapper.readValue(json, ApprovalPlan.class);

        assertThat(deserialized).isEqualTo(plan);
        var roundTripped = (InfraDesiredNodeSpec) deserialized.originalSpec();
        assertThat(roundTripped.resourceSpec()).isInstanceOf(K8sDeploymentSpec.class);
    }

    @Test
    void specEqualityPreservedAfterRoundTrip() throws Exception {
        var originalSpec = new InfraDesiredNodeSpec(new K8sNamespaceSpec("staging", Labels.empty()), "cluster-3");
        var plan = new ApprovalPlan(
                NodeId.of("ns-staging"), StepAction.PROVISION, RiskClassification.LOW,
                "Provision namespace 'staging'",
                "tenant-1", originalSpec, null);

        String       json         = mapper.writeValueAsString(plan);
        ApprovalPlan deserialized = mapper.readValue(json, ApprovalPlan.class);

        var freshSpec = new InfraDesiredNodeSpec(new K8sNamespaceSpec("staging", Labels.empty()), "cluster-3");
        assertThat(deserialized.originalSpec()).isEqualTo(freshSpec);

        var differentSpec = new InfraDesiredNodeSpec(new K8sNamespaceSpec("production", Labels.empty()), "cluster-3");
        assertThat(deserialized.originalSpec()).isNotEqualTo(differentSpec);
    }

    @Test
    void nullDetailHandled() throws Exception {
        var spec = new InfraDesiredNodeSpec(new K8sNamespaceSpec("dev", Labels.empty()), "cluster-1");
        var plan = new ApprovalPlan(
                NodeId.of("ns-dev"), StepAction.PROVISION, RiskClassification.LOW,
                "Provision namespace 'dev'",
                "tenant-1", spec, null);

        String       json         = mapper.writeValueAsString(plan);
        ApprovalPlan deserialized = mapper.readValue(json, ApprovalPlan.class);

        assertThat(deserialized.detail()).isNull();
        assertThat(deserialized).isEqualTo(plan);
    }
}
