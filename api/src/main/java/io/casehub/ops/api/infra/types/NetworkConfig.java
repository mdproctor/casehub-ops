package io.casehub.ops.api.infra.types;

import java.util.List;
import java.util.Objects;

public record NetworkConfig(String vpcId, String subnetId, List<String> securityGroups) {

    public NetworkConfig {
        Objects.requireNonNull(vpcId, "vpcId");
        Objects.requireNonNull(subnetId, "subnetId");
        Objects.requireNonNull(securityGroups, "securityGroups");
        securityGroups = List.copyOf(securityGroups);
    }
}
