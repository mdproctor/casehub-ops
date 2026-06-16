package io.casehub.ops.api.deployment;

import java.util.Map;

public record TrustPolicyNodeSpec(
        String capability,
        double threshold,
        int minimumObservations,
        double borderlineMargin,
        double blendFactor,
        Map<String, Double> qualityFloors,
        boolean bootstrapEscalationRequired
) implements DeploymentNodeSpec {

    public TrustPolicyNodeSpec {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability is required");
        }
        qualityFloors = qualityFloors != null ? Map.copyOf(qualityFloors) : Map.of();
    }

    @Override
    public String nodeId() {
        return capability;
    }

    @Override
    public String nodeType() {
        return "trust_policy";
    }
}
