package io.casehub.ops.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.GenericResourceSpec;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.api.infra.goal.ImportDeclaration;
import io.casehub.ops.api.infra.goal.InfraGoals;
import io.casehub.ops.api.infra.goal.ResourceDeclaration;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ops.api.infra.types.ServiceType;

class InfraGoalCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final InfraGoalCompiler compiler = new InfraGoalCompiler();
    private final DesiredStateGraphFactory factory = new io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory();

    // --- helpers ---

    private ObjectNode nsConfig(String name) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("name", name);
        return config;
    }

    private ObjectNode nsConfigWithLabels(String name, Map<String, String> labels) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("name", name);
        ObjectNode labelsNode = config.putObject("labels");
        labels.forEach(labelsNode::put);
        return config;
    }

    private ObjectNode deploymentConfig(String namespace, String name, String image,
                                         int replicas, Map<String, String> labels) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("namespace", namespace);
        config.put("name", name);
        config.put("image", image);
        config.put("replicas", replicas);

        ObjectNode resources = config.putObject("resources");
        resources.put("cpuRequest", "100m");
        resources.put("cpuLimit", "500m");
        resources.put("memoryRequest", "128Mi");
        resources.put("memoryLimit", "512Mi");

        ObjectNode labelsNode = config.putObject("labels");
        labels.forEach(labelsNode::put);

        return config;
    }

    private ObjectNode serviceConfig(String namespace, String name, int port,
                                      int targetPort, String serviceType) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("namespace", namespace);
        config.put("name", name);
        config.put("port", port);
        config.put("targetPort", targetPort);
        config.put("serviceType", serviceType);
        return config;
    }

    // --- test cases ---

    @Test
    void compilesK8sNamespaceToTypedSpec() {
        var goals = new InfraGoals(
                "terraform",
                List.of(new ResourceDeclaration("ns1", "k8s_namespace", null,
                        nsConfig("production"), List.of())),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("ns1"));
        assertThat(node.id()).isEqualTo(NodeId.of("ns1"));
        assertThat(node.type().value()).isEqualTo("k8s_namespace");
        assertThat(node.requiresHuman()).isFalse();

        InfraDesiredNodeSpec wrapper = (InfraDesiredNodeSpec) node.spec();
        assertThat(wrapper.backendId()).isEqualTo("terraform");

        K8sNamespaceSpec nsSpec = (K8sNamespaceSpec) wrapper.resourceSpec();
        assertThat(nsSpec.name()).isEqualTo("production");
        assertThat(nsSpec.labels()).isEqualTo(Labels.empty());
    }

    @Test
    void perResourceBackendOverridesDefault() {
        var goals = new InfraGoals(
                "standalone",
                List.of(new ResourceDeclaration("ns1", "k8s_namespace", "terraform",
                        nsConfig("staging"), List.of())),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        InfraDesiredNodeSpec wrapper = (InfraDesiredNodeSpec) graph.nodes().get(NodeId.of("ns1")).spec();
        assertThat(wrapper.backendId()).isEqualTo("terraform");
    }

    @Test
    void dependsOnCreatesDependencyEdges() {
        var goals = new InfraGoals(
                "standalone",
                List.of(
                        new ResourceDeclaration("ns1", "k8s_namespace", null,
                                nsConfig("production"), List.of()),
                        new ResourceDeclaration("d1", "k8s_namespace", null,
                                nsConfig("staging"), List.of("ns1"))),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        assertThat(graph.dependencies()).hasSize(1);
        Dependency dep = graph.dependencies().iterator().next();
        // "d1 dependsOn ns1" means d1 depends on ns1
        // Dependency(from, to) where "from depends on to"
        assertThat(dep.from()).isEqualTo(NodeId.of("d1"));
        assertThat(dep.to()).isEqualTo(NodeId.of("ns1"));
    }

    @Test
    void unknownTypeProducesGenericResourceSpec() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("foo", "bar");

        var goals = new InfraGoals(
                "standalone",
                List.of(new ResourceDeclaration("w1", "custom_widget", null,
                        config, List.of())),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        InfraDesiredNodeSpec wrapper = (InfraDesiredNodeSpec) graph.nodes().get(NodeId.of("w1")).spec();
        assertThat(wrapper.resourceSpec()).isInstanceOf(GenericResourceSpec.class);
        GenericResourceSpec generic = (GenericResourceSpec) wrapper.resourceSpec();
        assertThat(generic.resourceType()).isEqualTo("custom_widget");
        assertThat(generic.config().get("foo").asText()).isEqualTo("bar");
    }

    @Test
    void rejectsTerraformWorkspaceWithNonTerraformBackend() {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("workspacePath", "/opt/terraform/infra");
        ObjectNode state = config.putObject("state");
        state.put("type", "LOCAL");

        var withAnsible = new InfraGoals(null,
                List.of(new ResourceDeclaration("tf1", "terraform_workspace", "ansible", config, List.of())),
                List.of());
        assertThatThrownBy(() -> compiler.compile(withAnsible, factory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terraform_workspace")
                .hasMessageContaining("terraform");

        var withStandalone = new InfraGoals(null,
                List.of(new ResourceDeclaration("tf2", "terraform_workspace", "standalone", config, List.of())),
                List.of());
        assertThatThrownBy(() -> compiler.compile(withStandalone, factory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terraform_workspace")
                .hasMessageContaining("terraform");
    }

    @Test
    void fallsBackToStandaloneWhenNoBackendSpecified() {
        var goals = new InfraGoals(
                null,
                List.of(new ResourceDeclaration("ns1", "k8s_namespace", null,
                        nsConfig("default"), List.of())),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        InfraDesiredNodeSpec wrapper = (InfraDesiredNodeSpec) graph.nodes().get(NodeId.of("ns1")).spec();
        assertThat(wrapper.backendId()).isEqualTo("standalone");
    }

    @Test
    void compilesK8sDeploymentWithAllFields() {
        Map<String, String> labels = Map.of("app", "myapp", "tier", "backend");
        ObjectNode config = deploymentConfig("production", "my-deploy", "nginx:1.25", 3, labels);

        var goals = new InfraGoals(
                "terraform",
                List.of(new ResourceDeclaration("d1", "k8s_deployment", null,
                        config, List.of())),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        InfraDesiredNodeSpec wrapper = (InfraDesiredNodeSpec) graph.nodes().get(NodeId.of("d1")).spec();
        K8sDeploymentSpec deploy = (K8sDeploymentSpec) wrapper.resourceSpec();
        assertThat(deploy.namespace()).isEqualTo("production");
        assertThat(deploy.name()).isEqualTo("my-deploy");
        assertThat(deploy.image()).isEqualTo("nginx:1.25");
        assertThat(deploy.replicas()).isEqualTo(3);
        assertThat(deploy.resources()).isEqualTo(
                new ResourceRequirements("100m", "500m", "128Mi", "512Mi"));
        assertThat(deploy.labels().get("app")).hasValue("myapp");
        assertThat(deploy.labels().get("tier")).hasValue("backend");
    }

    @Test
    void compilesMultipleResourcesWithDependencies() {
        ObjectNode svcConfig = serviceConfig("production", "my-svc", 80, 8080, "CLUSTER_IP");

        var goals = new InfraGoals(
                "terraform",
                List.of(
                        new ResourceDeclaration("ns1", "k8s_namespace", null,
                                nsConfig("production"), List.of()),
                        new ResourceDeclaration("d1", "k8s_deployment", null,
                                deploymentConfig("production", "my-deploy", "nginx:1.25", 2,
                                        Map.of("app", "myapp")),
                                List.of("ns1")),
                        new ResourceDeclaration("s1", "k8s_service", null,
                                svcConfig,
                                List.of("d1"))),
                List.of());

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.dependencies()).hasSize(2);

        // d1 depends on ns1
        assertThat(graph.dependencies()).anySatisfy(dep -> {
            assertThat(dep.from()).isEqualTo(NodeId.of("d1"));
            assertThat(dep.to()).isEqualTo(NodeId.of("ns1"));
        });
        // s1 depends on d1
        assertThat(graph.dependencies()).anySatisfy(dep -> {
            assertThat(dep.from()).isEqualTo(NodeId.of("s1"));
            assertThat(dep.to()).isEqualTo(NodeId.of("d1"));
        });

        // verify types
        assertThat(graph.nodes().values()).anySatisfy(n -> {
            assertThat(n.id()).isEqualTo(NodeId.of("ns1"));
            assertThat(n.type().value()).isEqualTo("k8s_namespace");
        });
        assertThat(graph.nodes().values()).anySatisfy(n -> {
            assertThat(n.id()).isEqualTo(NodeId.of("d1"));
            assertThat(n.type().value()).isEqualTo("k8s_deployment");
        });
        assertThat(graph.nodes().values()).anySatisfy(n -> {
            assertThat(n.id()).isEqualTo(NodeId.of("s1"));
            assertThat(n.type().value()).isEqualTo("k8s_service");
            InfraDesiredNodeSpec w = (InfraDesiredNodeSpec) n.spec();
            K8sServiceSpec svc = (K8sServiceSpec) w.resourceSpec();
            assertThat(svc.port()).isEqualTo(80);
            assertThat(svc.targetPort()).isEqualTo(8080);
            assertThat(svc.serviceType()).isEqualTo(ServiceType.CLUSTER_IP);
        });
    }
}
