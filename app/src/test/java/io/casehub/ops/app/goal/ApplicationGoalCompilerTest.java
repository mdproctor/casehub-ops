package io.casehub.ops.app.goal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.app.model.ServiceDefinition;
import io.casehub.ops.api.infra.types.PortMapping;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApplicationGoalCompilerTest {

    private ApplicationGoalCompiler compiler;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        compiler = new ApplicationGoalCompiler();
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void compilesServiceToDeploymentAndServiceNodes() {
        var services = List.of(service("inventory", "quay.io/app:1.0", 2,
                List.of(new PortMapping(8080, 80, "TCP")), List.of(), List.of()));

        var graph = compiler.compileForCluster(services, "ops-prod", "casehub", factory);

        assertThat(graph.nodes()).hasSize(3);

        var nsNode = graph.nodes().values().stream()
                .filter(n -> unwrap(n.spec()).resourceType().equals("k8s_namespace"))
                .findFirst().orElseThrow();
        assertThat(((K8sNamespaceSpec) unwrap(nsNode.spec())).name()).isEqualTo("casehub");

        var deployNode = graph.nodes().values().stream()
                .filter(n -> unwrap(n.spec()).resourceType().equals("k8s_deployment"))
                .findFirst().orElseThrow();
        var deploySpec = (K8sDeploymentSpec) unwrap(deployNode.spec());
        assertThat(deploySpec.name()).isEqualTo("inventory");
        assertThat(deploySpec.image()).isEqualTo("quay.io/app:1.0");
        assertThat(deploySpec.replicas()).isEqualTo(2);

        var svcNode = graph.nodes().values().stream()
                .filter(n -> unwrap(n.spec()).resourceType().equals("k8s_service"))
                .findFirst().orElseThrow();
        var svcSpec = (K8sServiceSpec) unwrap(svcNode.spec());
        assertThat(svcSpec.port()).isEqualTo(80);
        assertThat(svcSpec.targetPort()).isEqualTo(8080);
    }

    @Test
    void respectsServiceDependencies() {
        var services = List.of(
                service("gateway", "img:1.0", 1, List.of(new PortMapping(8080, 80, "TCP")),
                        List.of("orders"), List.of()),
                service("orders", "img:1.0", 1, List.of(new PortMapping(8080, 80, "TCP")),
                        List.of(), List.of()));

        var graph = compiler.compileForCluster(services, "ops-prod", "casehub", factory);

        var gatewayDeploy = graph.nodes().keySet().stream()
                .filter(id -> id.value().equals("ops-prod:gateway:deployment"))
                .findFirst().orElseThrow();
        var ordersDeploy = graph.nodes().keySet().stream()
                .filter(id -> id.value().equals("ops-prod:orders:deployment"))
                .findFirst().orElseThrow();

        assertThat(graph.dependencies()).anyMatch(dep ->
                dep.from().equals(gatewayDeploy) && dep.to().equals(ordersDeploy));
    }

    @Test
    void filtersServicesByTargetClusters() {
        var services = List.of(
                service("gateway", "img:1.0", 1, List.of(new PortMapping(8080, 80, "TCP")),
                        List.of(), List.of()),
                service("orders", "img:1.0", 1, List.of(new PortMapping(8080, 80, "TCP")),
                        List.of(), List.of("ops-prod")));

        var stagingGraph = compiler.compileForCluster(services, "ops-staging", "casehub", factory);

        assertThat(stagingGraph.nodes().keySet().stream()
                .filter(id -> id.value().contains("orders"))
                .toList()).isEmpty();
        assertThat(stagingGraph.nodes().keySet().stream()
                .filter(id -> id.value().contains("gateway"))
                .toList()).isNotEmpty();
    }

    @Test
    void setsBackendIdWithClusterId() {
        var services = List.of(service("svc", "img:1.0", 1,
                List.of(new PortMapping(8080, 80, "TCP")), List.of(), List.of()));
        var graph = compiler.compileForCluster(services, "ops-prod", "casehub", factory);

        graph.nodes().values().forEach(node -> {
            var infraSpec = (InfraDesiredNodeSpec) node.spec();
            assertThat(infraSpec.backendId()).isEqualTo("kubernetes:ops-prod");
        });
    }

    private ServiceDefinition service(String id, String image, int replicas,
                                       List<PortMapping> ports, List<String> dependsOn,
                                       List<String> targetClusters) {
        return new ServiceDefinition(id, id, image, replicas, ports, Map.of(),
                new ResourceRequirements("500m", "1Gi", "250m", "512Mi"),
                dependsOn, Optional.empty(), targetClusters);
    }

    private io.casehub.ops.api.infra.InfraNodeSpec unwrap(io.casehub.desiredstate.api.NodeSpec spec) {
        return ((InfraDesiredNodeSpec) spec).resourceSpec();
    }
}
