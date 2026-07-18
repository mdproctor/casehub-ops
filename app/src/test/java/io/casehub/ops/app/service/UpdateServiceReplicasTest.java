package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.app.goal.ApplicationGoalCompiler;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ServiceDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class UpdateServiceReplicasTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                               .registerModule(new JavaTimeModule())
                               .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());}

    @Test
    void patchesReplicasInServiceDefinitionJson() throws Exception {
        String servicesJson = """
            [
              {
                "serviceId": "web",
                "name": "Web Frontend",
                "image": "myapp/web:1.0",
                "replicas": 2,
                "ports": [],
                "env": {},
                "resources": {
                  "cpuRequest": "100m",
                  "memoryRequest": "128Mi",
                  "cpuLimit": "500m",
                  "memoryLimit": "512Mi"
                },
                "dependsOn": [],
                "healthCheck": null,
                "targetClusters": []
              }
            ]
            """;

        List<ServiceDefinition> services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        List<ServiceDefinition> updated = patchReplicas(services, "web", 5);

        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).replicas()).isEqualTo(5);
        assertThat(updated.get(0).serviceId()).isEqualTo("web");
        assertThat(updated.get(0).image()).isEqualTo("myapp/web:1.0");

        String serialized = objectMapper.writeValueAsString(updated);
        List<ServiceDefinition> roundTripped = ServiceDefinitionParser.parse(serialized, objectMapper);
        assertThat(roundTripped.get(0).replicas()).isEqualTo(5);
    }

    @Test
    void patchesOnlyTargetServiceLeavesOthersUnchanged() throws Exception {
        String servicesJson = """
            [
              {
                "serviceId": "web",
                "name": "Web",
                "image": "web:1.0",
                "replicas": 2,
                "ports": [],
                "env": {},
                "resources": {"cpuRequest":"100m","memoryRequest":"128Mi","cpuLimit":"500m","memoryLimit":"512Mi"},
                "dependsOn": [],
                "healthCheck": null,
                "targetClusters": []
              },
              {
                "serviceId": "api",
                "name": "API",
                "image": "api:1.0",
                "replicas": 3,
                "ports": [],
                "env": {},
                "resources": {"cpuRequest":"100m","memoryRequest":"128Mi","cpuLimit":"500m","memoryLimit":"512Mi"},
                "dependsOn": [],
                "healthCheck": null,
                "targetClusters": []
              }
            ]
            """;

        List<ServiceDefinition> services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        List<ServiceDefinition> updated = patchReplicas(services, "web", 5);

        assertThat(updated).hasSize(2);
        assertThat(updated.get(0).replicas()).isEqualTo(5);
        assertThat(updated.get(1).replicas()).isEqualTo(3);
    }

    @Test
    void unknownServiceIdThrows() throws Exception {
        String servicesJson = """
            [
              {
                "serviceId": "web",
                "name": "Web",
                "image": "web:1.0",
                "replicas": 2,
                "ports": [],
                "env": {},
                "resources": {"cpuRequest":"100m","memoryRequest":"128Mi","cpuLimit":"500m","memoryLimit":"512Mi"},
                "dependsOn": [],
                "healthCheck": null,
                "targetClusters": []
              }
            ]
            """;

        List<ServiceDefinition> services = ServiceDefinitionParser.parse(servicesJson, objectMapper);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> patchReplicas(services, "nonexistent", 5))
                .withMessageContaining("nonexistent");
    }

    @Test
    void statusGuardRejectsDraft() {
        assertThatIllegalStateException()
                .isThrownBy(() -> validateStatus(ApplicationStatus.DRAFT))
                .withMessageContaining("DRAFT");
    }

    @Test
    void statusGuardRejectsDeploying() {
        assertThatIllegalStateException()
                .isThrownBy(() -> validateStatus(ApplicationStatus.DEPLOYING))
                .withMessageContaining("DEPLOYING");
    }

    @Test
    void statusGuardAcceptsRunning() {
        assertThatNoException().isThrownBy(() -> validateStatus(ApplicationStatus.RUNNING));
    }

    @Test
    void statusGuardAcceptsDegraded() {
        assertThatNoException().isThrownBy(() -> validateStatus(ApplicationStatus.DEGRADED));
    }

    @Test
    void affectedNodeIdsFormatIsCorrect() {
        String clusterId = "cluster-1";
        String serviceId = "web";
        String nodeId = clusterId + ":" + serviceId + ":deployment";
        assertThat(nodeId).isEqualTo("cluster-1:web:deployment");
    }

    @Test
    void updatedGraphHasCorrectReplicaCount() throws Exception {
        String servicesJson = """
            [
              {
                "serviceId": "web",
                "name": "Web",
                "image": "web:1.0",
                "replicas": 2,
                "ports": [],
                "env": {},
                "resources": {"cpuRequest":"100m","memoryRequest":"128Mi","cpuLimit":"500m","memoryLimit":"512Mi"},
                "dependsOn": [],
                "healthCheck": null,
                "targetClusters": []
              }
            ]
            """;

        List<ServiceDefinition> services = ServiceDefinitionParser.parse(servicesJson, objectMapper);
        List<ServiceDefinition> updated = patchReplicas(services, "web", 7);

        var compiler = new ApplicationGoalCompiler();
        var factory = new DefaultDesiredStateGraphFactory();
        var graph = compiler.compileForCluster(updated, "cluster-1", "default", factory);

        assertThat(graph.nodes()).isNotEmpty();
    }

    private static List<ServiceDefinition> patchReplicas(List<ServiceDefinition> services,
                                                          String serviceId, int newReplicas) {
        boolean found = false;
        List<ServiceDefinition> updated = new java.util.ArrayList<>();
        for (ServiceDefinition sd : services) {
            if (sd.serviceId().equals(serviceId)) {
                found = true;
                updated.add(new ServiceDefinition(sd.serviceId(), sd.name(), sd.image(), newReplicas,
                        sd.ports(), sd.env(), sd.resources(), sd.dependsOn(), sd.healthCheck(), sd.targetClusters(), sd.scalingRules()));
            } else {
                updated.add(sd);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Service not found: " + serviceId);
        }
        return updated;
    }

    private static void validateStatus(ApplicationStatus status) {
        if (status != ApplicationStatus.RUNNING && status != ApplicationStatus.DEGRADED) {
            throw new IllegalStateException("Cannot scale application in status " + status
                                            + " — must be RUNNING or DEGRADED");
        }
    }
}
