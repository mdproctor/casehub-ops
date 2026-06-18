package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceGoals(List<ComplianceGoalEntry> controls) {
    public ComplianceGoals {
        controls = controls != null ? List.copyOf(controls) : List.of();
    }
}
