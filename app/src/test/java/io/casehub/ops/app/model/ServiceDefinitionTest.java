package io.casehub.ops.app.model;

import io.casehub.ops.api.infra.types.HealthCheckSpec;
import io.casehub.ops.api.infra.types.PortMapping;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ServiceDefinitionTest {

    @Test
    void createsValidServiceDefinition() {
        var sd = new ServiceDefinition(
                "inventory-svc", "Inventory Service",
                "quay.io/casehub/inventory:1.0.0",
                2,
                List.of(new PortMapping(8080, 80, "TCP")),
                Map.of("JAVA_OPTS", "-Xmx512m"),
                new ResourceRequirements("500m", "1Gi", "250m", "512Mi"),
                List.of(),
                Optional.of(new HealthCheckSpec("/q/health", 8080, 5, 10)),
                List.of(), List.of());
        assertThat(sd.serviceId()).isEqualTo("inventory-svc");
        assertThat(sd.image()).isEqualTo("quay.io/casehub/inventory:1.0.0");
        assertThat(sd.replicas()).isEqualTo(2);
        assertThat(sd.ports()).hasSize(1);
        assertThat(sd.dependsOn()).isEmpty();
        assertThat(sd.targetClusters()).isEmpty();
    }

    @Test
    void emptyTargetClustersMeansAllClusters() {
        var sd = new ServiceDefinition(
                "gateway", "Gateway", "img:1.0", 1,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(), List.of());
        assertThat(sd.targetClusters()).isEmpty();
    }

    @Test
    void specificTargetClustersFiltersDeployment() {
        var sd = new ServiceDefinition(
                "orders", "Orders", "img:1.0", 2,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of("inventory"),
                Optional.empty(),
                List.of("ops-prod"), List.of());
        assertThat(sd.targetClusters()).containsExactly("ops-prod");
        assertThat(sd.dependsOn()).containsExactly("inventory");
    }

    @Test
    void rejectsNullServiceId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ServiceDefinition(
                        null, "name", "img:1.0", 1,
                        List.of(), Map.of(),
                        new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                        List.of(), Optional.empty(), List.of(), List.of()));
    }

    @Test
    void scalingRulesArePreserved() {
        var rules = List.of(
                new ScalingRule("high-load", 0.5, 2, 10, java.time.Duration.ofMinutes(5)));
        var sd = new ServiceDefinition(
                "web", "Web", "img:1.0", 2,
                List.of(), Map.of(),
                new ResourceRequirements("100m", "256Mi", "50m", "128Mi"),
                List.of(), Optional.empty(), List.of(), rules);
        assertThat(sd.scalingRules()).hasSize(1);
        assertThat(sd.scalingRules().get(0).situationId()).isEqualTo("high-load");
    }

}
