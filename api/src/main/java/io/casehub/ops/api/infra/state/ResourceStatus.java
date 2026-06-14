package io.casehub.ops.api.infra.state;

public enum ResourceStatus {
    HEALTHY,
    DRIFTED,
    DEGRADED,
    UNAVAILABLE,
    PROVISIONING,
    UNKNOWN
}
