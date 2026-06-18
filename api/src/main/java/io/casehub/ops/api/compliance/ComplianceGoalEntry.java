package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceGoalEntry(
        ComplianceControlSpec spec,
        List<String> dependsOn
) {
    public ComplianceGoalEntry {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
