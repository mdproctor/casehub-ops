package io.casehub.ops.app.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record DriftReport(
        List<NodeDrift> driftDetails,
        String clusterId,
        String applicationId,
        Instant detectedAt,
        int consecutiveDriftCount) {

    private static final Set<String> SECURITY_FIELDS =
            Set.of("image", "serviceAccount", "rbac", "secrets");

    public List<String> driftedNodeIds() {
        return driftDetails.stream().map(NodeDrift::nodeId).toList();
    }

    public boolean hasSecuritySensitiveFields() {
        return driftDetails.stream()
                .flatMap(nd -> nd.fields().stream())
                .anyMatch(f -> SECURITY_FIELDS.contains(f.fieldName()));
    }
}
