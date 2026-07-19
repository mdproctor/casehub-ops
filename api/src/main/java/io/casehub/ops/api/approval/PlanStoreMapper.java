package io.casehub.ops.api.approval;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.CaseTypeNodeSpec;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.ops.api.deployment.DeploymentNodeSpec;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.api.infra.plan.ToolPlanDetail;

public final class PlanStoreMapper {

    private static final ObjectMapper MAPPER = createMapper();

    private PlanStoreMapper() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.findAndRegisterModules();
        mapper.addMixIn(NodeSpec.class, NodeSpecMixin.class);
        mapper.addMixIn(InfraNodeSpec.class, InfraNodeSpecMixin.class);
        mapper.addMixIn(DeploymentNodeSpec.class, DeploymentNodeSpecMixin.class);
        mapper.addMixIn(PlanDetail.class, PlanDetailMixin.class);
        return mapper;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@specType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = InfraDesiredNodeSpec.class, name = "infra-desired"),
            @JsonSubTypes.Type(value = AgentNodeSpec.class, name = "deployment-agent"),
            @JsonSubTypes.Type(value = ChannelNodeSpec.class, name = "deployment-channel"),
            @JsonSubTypes.Type(value = CaseTypeNodeSpec.class, name = "deployment-case-type"),
            @JsonSubTypes.Type(value = TrustPolicyNodeSpec.class, name = "deployment-trust-policy"),
            @JsonSubTypes.Type(value = EndpointNodeSpec.class, name = "deployment-endpoint"),
    })
    interface NodeSpecMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = K8sNamespaceSpec.class, name = "k8s-namespace"),
            @JsonSubTypes.Type(value = K8sDeploymentSpec.class, name = "k8s-deployment"),
            @JsonSubTypes.Type(value = K8sServiceSpec.class, name = "k8s-service"),
            @JsonSubTypes.Type(value = K8sIngressSpec.class, name = "k8s-ingress"),
            @JsonSubTypes.Type(value = K8sConfigMapSpec.class, name = "k8s-configmap"),
            @JsonSubTypes.Type(value = ComputeInstanceSpec.class, name = "compute"),
            @JsonSubTypes.Type(value = DatabaseClusterSpec.class, name = "database"),
            @JsonSubTypes.Type(value = TerraformWorkspaceSpec.class, name = "terraform"),
            @JsonSubTypes.Type(value = AnsiblePlaybookSpec.class, name = "ansible"),
            @JsonSubTypes.Type(value = GenericResourceSpec.class, name = "generic"),
    })
    interface InfraNodeSpecMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AgentNodeSpec.class, name = "agent"),
            @JsonSubTypes.Type(value = ChannelNodeSpec.class, name = "channel"),
            @JsonSubTypes.Type(value = CaseTypeNodeSpec.class, name = "case-type"),
            @JsonSubTypes.Type(value = TrustPolicyNodeSpec.class, name = "trust-policy"),
            @JsonSubTypes.Type(value = EndpointNodeSpec.class, name = "endpoint"),
    })
    interface DeploymentNodeSpecMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@detailType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ToolPlanDetail.TerraformPlanDetail.class, name = "terraform"),
            @JsonSubTypes.Type(value = ToolPlanDetail.AnsibleCheckDetail.class, name = "ansible-check"),
            @JsonSubTypes.Type(value = ToolPlanDetail.StandaloneDiffDetail.class, name = "standalone-diff"),
    })
    interface PlanDetailMixin {}
}
