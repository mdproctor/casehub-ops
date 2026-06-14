package io.casehub.ops.api.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.ops.api.infra.types.AnsibleExtraVars;
import io.casehub.ops.api.infra.types.AnsibleInventory;
import io.casehub.ops.api.infra.types.BackupConfig;
import io.casehub.ops.api.infra.types.CloudProvider;
import io.casehub.ops.api.infra.types.ClusterSize;
import io.casehub.ops.api.infra.types.DatabaseEngine;
import io.casehub.ops.api.infra.types.IngressRule;
import io.casehub.ops.api.infra.types.InstanceType;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.NetworkConfig;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ops.api.infra.types.ServiceType;
import io.casehub.ops.api.infra.types.TerraformBackendConfig;
import io.casehub.ops.api.infra.types.TerraformStateType;

class InfraNodeSpecTest {

    @Test
    void infraNodeSpec_doesNotExtendNodeSpec() {
        assertThat(NodeSpec.class.isAssignableFrom(InfraNodeSpec.class)).isFalse();
    }

    @Test
    void infraDesiredNodeSpec_implementsNodeSpec() {
        var spec = new K8sNamespaceSpec("default", Labels.empty());
        var desired = new InfraDesiredNodeSpec(spec, "terraform-main");

        assertThat(desired).isInstanceOf(NodeSpec.class);
    }

    @Test
    void k8sNamespaceSpec_resourceType() {
        var spec = new K8sNamespaceSpec("production", Labels.of(Map.of("env", "prod")));

        assertThat(spec.resourceType()).isEqualTo("k8s_namespace");
        assertThat(spec.name()).isEqualTo("production");
    }

    @Test
    void k8sDeploymentSpec_fieldsAccessible() {
        var resources = new ResourceRequirements("100m", "500m", "128Mi", "512Mi");
        var labels = Labels.of(Map.of("app", "myapp"));
        var spec = new K8sDeploymentSpec("default", "my-deploy", "nginx:latest", 3, resources, labels);

        assertThat(spec.resourceType()).isEqualTo("k8s_deployment");
        assertThat(spec.namespace()).isEqualTo("default");
        assertThat(spec.name()).isEqualTo("my-deploy");
        assertThat(spec.image()).isEqualTo("nginx:latest");
        assertThat(spec.replicas()).isEqualTo(3);
        assertThat(spec.resources()).isEqualTo(resources);
        assertThat(spec.labels().get("app")).hasValue("myapp");
    }

    @Test
    void infraDesiredNodeSpec_wrapsSpecAndBackendId() {
        var nsSpec = new K8sNamespaceSpec("staging", Labels.empty());
        var desired = new InfraDesiredNodeSpec(nsSpec, "terraform-staging");

        assertThat(desired.resourceSpec()).isSameAs(nsSpec);
        assertThat(desired.backendId()).isEqualTo("terraform-staging");
    }

    @Test
    void sealedHierarchy_patternMatching() {
        InfraNodeSpec spec = new K8sNamespaceSpec("test", Labels.empty());

        String result = switch (spec) {
            case K8sNamespaceSpec ns -> "namespace:" + ns.name();
            case K8sDeploymentSpec d -> "deployment:" + d.name();
            case K8sServiceSpec s -> "service:" + s.name();
            case K8sIngressSpec i -> "ingress:" + i.name();
            case ComputeInstanceSpec c -> "compute:" + c.provider();
            case DatabaseClusterSpec db -> "db:" + db.engine();
            case TerraformWorkspaceSpec tf -> "tf:" + tf.workspacePath();
            case AnsiblePlaybookSpec ap -> "ansible:" + ap.playbookPath();
            case GenericResourceSpec g -> "generic:" + g.resourceType();
        };

        assertThat(result).isEqualTo("namespace:test");
    }

    @Test
    void labels_getAndEmpty() {
        var labels = Labels.of(Map.of("env", "prod", "tier", "frontend"));

        assertThat(labels.get("env")).hasValue("prod");
        assertThat(labels.get("missing")).isEmpty();

        var empty = Labels.empty();
        assertThat(empty.values()).isEmpty();
        assertThat(empty.get("anything")).isEmpty();
    }

    @Test
    void labels_defensiveCopy() {
        var mutable = new java.util.HashMap<>(Map.of("key", "value"));
        var labels = Labels.of(mutable);
        mutable.put("injected", "bad");

        assertThat(labels.get("injected")).isEmpty();
    }

    @Test
    void terraformWorkspaceSpec_resourceType() {
        var backend = new TerraformBackendConfig(TerraformStateType.S3, "my-bucket", "infra/state.tfstate");
        var spec = new TerraformWorkspaceSpec("/opt/terraform/infra", backend);

        assertThat(spec.resourceType()).isEqualTo("terraform_workspace");
        assertThat(spec.workspacePath()).isEqualTo("/opt/terraform/infra");
        assertThat(spec.state().type()).isEqualTo(TerraformStateType.S3);
    }

    @Test
    void ansiblePlaybookSpec_resourceType() {
        var inventory = new AnsibleInventory("/etc/ansible/hosts", "webservers");
        var extraVars = AnsibleExtraVars.empty();
        var spec = new AnsiblePlaybookSpec("/opt/ansible/site.yml", inventory, extraVars);

        assertThat(spec.resourceType()).isEqualTo("ansible_playbook");
        assertThat(spec.playbookPath()).isEqualTo("/opt/ansible/site.yml");
        assertThat(spec.inventory().hostGroup()).isEqualTo("webservers");
    }

    @Test
    void ansibleExtraVars_defensiveCopy() {
        var mutable = new java.util.HashMap<>(Map.of("key", "value"));
        var vars = new AnsibleExtraVars(mutable);
        mutable.put("injected", "bad");

        assertThat(vars.get("injected")).isEmpty();
        assertThat(vars.get("key")).hasValue("value");
    }

    @Test
    void k8sIngressSpec_defensiveCopyOnRules() {
        var rules = new java.util.ArrayList<>(List.of(
                new IngressRule("/api", "api-svc", 8080)));
        var spec = new K8sIngressSpec("default", "my-ingress", "example.com", rules, Labels.empty());
        rules.add(new IngressRule("/hack", "evil-svc", 666));

        assertThat(spec.rules()).hasSize(1);
        assertThat(spec.resourceType()).isEqualTo("k8s_ingress");
    }

    @Test
    void networkConfig_defensiveCopyOnSecurityGroups() {
        var groups = new java.util.ArrayList<>(List.of("sg-123"));
        var config = new NetworkConfig("vpc-1", "subnet-1", groups);
        groups.add("sg-injected");

        assertThat(config.securityGroups()).hasSize(1);
    }

    @Test
    void k8sServiceSpec_resourceType() {
        var spec = new K8sServiceSpec("default", "my-svc", 80, 8080,
                ServiceType.LOAD_BALANCER, Labels.empty());

        assertThat(spec.resourceType()).isEqualTo("k8s_service");
        assertThat(spec.serviceType()).isEqualTo(ServiceType.LOAD_BALANCER);
    }

    @Test
    void computeInstanceSpec_resourceType() {
        var network = new NetworkConfig("vpc-1", "subnet-1", List.of("sg-1"));
        var spec = new ComputeInstanceSpec(CloudProvider.AWS, "us-east-1",
                new InstanceType("m5", "xlarge"), "ami-12345", network);

        assertThat(spec.resourceType()).isEqualTo("compute_instance");
        assertThat(spec.provider()).isEqualTo(CloudProvider.AWS);
    }

    @Test
    void databaseClusterSpec_resourceType() {
        var backup = new BackupConfig(true, 7, "0 3 * * *");
        var spec = new DatabaseClusterSpec(DatabaseEngine.POSTGRESQL, "15.4",
                new ClusterSize(3, "gp3"), "eu-west-1", backup);

        assertThat(spec.resourceType()).isEqualTo("database_cluster");
        assertThat(spec.engine()).isEqualTo(DatabaseEngine.POSTGRESQL);
    }

    @Test
    void genericResourceSpec_withJsonNode() {
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("foo", "bar");

        var spec = new GenericResourceSpec("custom_thing", config);

        assertThat(spec.resourceType()).isEqualTo("custom_thing");
        assertThat(spec.config().get("foo").asText()).isEqualTo("bar");
    }

    @Test
    void terraformBackendConfig_localType_nullBucketAndKey() {
        var config = new TerraformBackendConfig(TerraformStateType.LOCAL, null, null);

        assertThat(config.type()).isEqualTo(TerraformStateType.LOCAL);
        assertThat(config.bucket()).isNull();
        assertThat(config.key()).isNull();
    }
}
