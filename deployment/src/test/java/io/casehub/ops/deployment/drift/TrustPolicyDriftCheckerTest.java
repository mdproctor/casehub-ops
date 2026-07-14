package io.casehub.ops.deployment.drift;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.deployment.DeploymentTrustRoutingPolicyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrustPolicyDriftCheckerTest {

    private TrustPolicyDriftChecker checker;
    private DeploymentTrustRoutingPolicyProvider policyProvider;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        policyProvider = new DeploymentTrustRoutingPolicyProvider();
        checker = new TrustPolicyDriftChecker(policyProvider);
    }

    @Test
    void nodeType() {
        assertEquals("trust_policy", checker.nodeType());
    }

    @Test
    void trustPolicyPresent() {
        var spec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);
        var policy = new TrustRoutingPolicy(0.8, 5, 0.1, 0.5, Map.of(), false, null, Set.of());
        policyProvider.store("cap-a", policy);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void trustPolicyAbsent() {
        var spec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);

        assertEquals(NodeStatus.ABSENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void trustPolicyDrifted_thresholdMismatch() {
        var spec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);
        var policy = new TrustRoutingPolicy(0.7, 5, 0.1, 0.5, Map.of(), false, null, Set.of());
        policyProvider.store("cap-a", policy);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void trustPolicyDrifted_minimumObservationsMismatch() {
        var spec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);
        var policy = new TrustRoutingPolicy(0.8, 3, 0.1, 0.5, Map.of(), false, null, Set.of());
        policyProvider.store("cap-a", policy);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void trustPolicyDrifted_qualityFloorsMismatch() {
        var spec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of("acc", 0.6), false);
        var policy = new TrustRoutingPolicy(0.8, 5, 0.1, 0.5, Map.of("acc", 0.5), false, null, Set.of());
        policyProvider.store("cap-a", policy);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void unknownSpecType() {
        var spec = new io.casehub.ops.api.deployment.AgentNodeSpec(
                "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(), null, "US", "policy", null, List.of());

        assertEquals(NodeStatus.UNKNOWN, checker.check(spec, TENANCY_ID));
    }
}
