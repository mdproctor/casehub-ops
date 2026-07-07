package io.casehub.ops.api.infra;

public sealed interface InfraNodeSpec
        permits K8sNamespaceSpec, K8sDeploymentSpec, K8sServiceSpec, K8sIngressSpec,
                K8sConfigMapSpec,
                ComputeInstanceSpec, DatabaseClusterSpec,
                TerraformWorkspaceSpec, AnsiblePlaybookSpec,
                GenericResourceSpec {

    String resourceType();
}
