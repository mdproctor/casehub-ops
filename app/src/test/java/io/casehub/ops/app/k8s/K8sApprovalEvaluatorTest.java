package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sConfigMapSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ops.api.infra.types.ServiceType;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class K8sApprovalEvaluatorTest {

    private final K8sApprovalEvaluator evaluator = new K8sApprovalEvaluator();

    @Test
    void namespaceDeprovision_isCritical_requiresApproval() {
        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod-billing", Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("ns-1"),
                ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.CRITICAL);
        assertThat(req.plan().summary()).contains("Deprovision").contains("prod-billing");
    }

    @Test
    void namespaceProvision_isLow_autoApproves() {
        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("dev-sandbox", Labels.empty()),
                "kubernetes:ops-dev");
        var node = new DesiredNode(NodeId.of("ns-2"),
                ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void deploymentDeprovision_isHigh_requiresApproval() {
        var spec = new InfraDesiredNodeSpec(
                new K8sDeploymentSpec("prod", "api-server", "myapp:1.0", 3,
                        new ResourceRequirements("250m", "500m", "256Mi", "512Mi"),
                        Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("dep-1"),
                ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.HIGH);
    }

    @Test
    void deploymentProvision_isMedium_autoApproves() {
        var spec = new InfraDesiredNodeSpec(
                new K8sDeploymentSpec("staging", "web", "app:2.0", 2,
                        new ResourceRequirements("100m", "200m", "128Mi", "256Mi"),
                        Labels.empty()),
                "kubernetes:ops-staging");
        var node = new DesiredNode(NodeId.of("dep-2"),
                ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void serviceDeprovision_isMedium_autoApproves() {
        var spec = new InfraDesiredNodeSpec(
                new K8sServiceSpec("prod", "api", 80, 8080,
                        ServiceType.CLUSTER_IP, Labels.empty(), Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("svc-1"),
                ApplicationNodeTypes.K8S_SERVICE, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void configmapDeprovision_isMedium_autoApproves() {
        var spec = new InfraDesiredNodeSpec(
                new K8sConfigMapSpec("prod", "app-config", Map.of("key", "val"), Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("cm-1"),
                ApplicationNodeTypes.K8S_CONFIGMAP, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void nonInfraSpec_autoApproves() {
        record TestNodeSpec() implements NodeSpec {}
        var node = new DesiredNode(NodeId.of("other-1"),
                NodeType.of("unknown"), new TestNodeSpec(), HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void summary_includesClusterAndResourceDetails() {
        var spec = new InfraDesiredNodeSpec(
                new K8sDeploymentSpec("prod", "api-server", "myapp:1.0", 3,
                        new ResourceRequirements("250m", "500m", "256Mi", "512Mi"),
                        Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("dep-1"),
                ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().summary())
                .contains("Deprovision")
                .contains("prod/api-server")
                .contains("kubernetes:ops-prod");
    }

    @Test
    void deploymentProvision_inProductionNamespace_isElevatedToHigh() {
        var evaluator = new K8sApprovalEvaluator(
                java.util.Set.of(), java.util.Set.of("production", "prod"));
        var spec = new InfraDesiredNodeSpec(
                new K8sDeploymentSpec("production", "web", "app:2.0", 2,
                                      new ResourceRequirements("100m", "200m", "128Mi", "256Mi"),
                                      Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("dep-ctx-1"),
                                   ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.HIGH);
    }

    @Test
    void deploymentDeprovision_inCriticalNamespace_isElevatedToCritical() {
        var evaluator = new K8sApprovalEvaluator(
                java.util.Set.of("kube-system", "istio-system"), java.util.Set.of());
        var spec = new InfraDesiredNodeSpec(
                new K8sDeploymentSpec("kube-system", "coredns", "coredns:1.11", 2,
                                      new ResourceRequirements("100m", "200m", "128Mi", "256Mi"),
                                      Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("dep-ctx-2"),
                                   ApplicationNodeTypes.K8S_DEPLOYMENT, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.CRITICAL);
    }

    @Test
    void serviceProvision_inDevNamespace_usesBaseClassification() {
        var evaluator = new K8sApprovalEvaluator(
                java.util.Set.of("kube-system"), java.util.Set.of("production"));
        var spec = new InfraDesiredNodeSpec(
                new K8sServiceSpec("dev", "my-svc", 80, 8080,
                                   ServiceType.CLUSTER_IP, Labels.empty(), Labels.empty()),
                "kubernetes:ops-dev");
        var node = new DesiredNode(NodeId.of("svc-ctx-1"),
                                   ApplicationNodeTypes.K8S_SERVICE, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void namespaceDeprovision_criticalByName_remainsCritical() {
        var evaluator = new K8sApprovalEvaluator(
                java.util.Set.of("prod-billing"), java.util.Set.of());
        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod-billing", Labels.empty()),
                "kubernetes:ops-prod");
        var node = new DesiredNode(NodeId.of("ns-ctx-1"),
                                   ApplicationNodeTypes.K8S_NAMESPACE, spec, HumanGating.NONE);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.RequiresApproval.class);
        var req = (ApprovalDecision.RequiresApproval) decision;
        assertThat(req.plan().risk()).isEqualTo(RiskClassification.CRITICAL);
    }


}
