package io.casehub.ops.deployment;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrustPolicyProvisionHandlerTest {

    private DeploymentTrustRoutingPolicyProvider policyProvider;
    private TrustPolicyProvisionHandler handler;
    private DesiredStateGraph emptyGraph;

    @BeforeEach
    void setUp() {
        policyProvider = new DeploymentTrustRoutingPolicyProvider();
        handler = new TrustPolicyProvisionHandler(policyProvider);
        emptyGraph = new DefaultDesiredStateGraphFactory().empty();
    }

    @Test
    void provisionStoresPolicy() {
        TrustPolicyNodeSpec spec = new TrustPolicyNodeSpec(
                "analyze-contracts",
                0.8,
                15,
                0.15,
                0.7,
                Map.of("accuracy", 0.9, "completeness", 0.85),
                true
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        ProvisionResult result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        TrustRoutingPolicy stored = policyProvider.forCapability("analyze-contracts");
        assertThat(stored.threshold()).isEqualTo(0.8);
        assertThat(stored.minimumObservations()).isEqualTo(15);
        assertThat(stored.borderlineMargin()).isEqualTo(0.15);
        assertThat(stored.blendFactor()).isEqualTo(0.7);
        assertThat(stored.qualityFloors()).containsEntry("accuracy", 0.9);
        assertThat(stored.qualityFloors()).containsEntry("completeness", 0.85);
        assertThat(stored.bootstrapEscalationRequired()).isTrue();
    }

    @Test
    void undeclaredCapabilityReturnsDEFAULT() {
        TrustRoutingPolicy policy = policyProvider.forCapability("unknown-capability");
        assertThat(policy).isEqualTo(TrustRoutingPolicy.DEFAULT);
    }

    @Test
    void deprovisionRevertsToDefault() {
        TrustPolicyNodeSpec spec = new TrustPolicyNodeSpec(
                "analyze-contracts",
                0.8,
                15,
                0.15,
                0.7,
                Map.of(),
                false
        );

        ProvisionContext provisionContext = new ProvisionContext("tenant-1", emptyGraph);
        handler.provision(spec, provisionContext);

        TrustRoutingPolicy beforeDeprovision = policyProvider.forCapability("analyze-contracts");
        assertThat(beforeDeprovision.threshold()).isEqualTo(0.8);

        DeprovisionContext deprovisionContext = new DeprovisionContext("tenant-1", emptyGraph);
        DeprovisionResult result = handler.deprovision(spec, deprovisionContext);

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);

        TrustRoutingPolicy afterDeprovision = policyProvider.forCapability("analyze-contracts");
        assertThat(afterDeprovision).isEqualTo(TrustRoutingPolicy.DEFAULT);
    }

    @Test
    void provisionIsIdempotent() {
        TrustPolicyNodeSpec spec1 = new TrustPolicyNodeSpec(
                "analyze-contracts",
                0.8,
                15,
                0.15,
                0.7,
                Map.of("accuracy", 0.9),
                false
        );

        TrustPolicyNodeSpec spec2 = new TrustPolicyNodeSpec(
                "analyze-contracts",
                0.85,
                20,
                0.2,
                0.75,
                Map.of("completeness", 0.95),
                true
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        handler.provision(spec1, context);
        handler.provision(spec2, context);

        TrustRoutingPolicy stored = policyProvider.forCapability("analyze-contracts");
        assertThat(stored.threshold()).isEqualTo(0.85);
        assertThat(stored.minimumObservations()).isEqualTo(20);
        assertThat(stored.borderlineMargin()).isEqualTo(0.2);
        assertThat(stored.blendFactor()).isEqualTo(0.75);
        assertThat(stored.qualityFloors()).containsEntry("completeness", 0.95);
        assertThat(stored.qualityFloors()).doesNotContainKey("accuracy");
        assertThat(stored.bootstrapEscalationRequired()).isTrue();
    }
}
