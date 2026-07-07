package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.desiredstate.api.NodeSpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceControlSpec(
        String controlId,
        String controlType,
        String strategy,
        String title,
        String description,
        List<FrameworkMapping> frameworks,
        int evidenceMaxAgeDays,
        boolean requiresHumanReview,
        Map<String, Object> properties
) implements NodeSpec {
    public ComplianceControlSpec {
        if (controlId == null || controlId.isBlank())
            throw new IllegalArgumentException("controlId is required");
        if (controlType == null || controlType.isBlank())
            throw new IllegalArgumentException("controlType is required");
        if (strategy == null || strategy.isBlank())
            throw new IllegalArgumentException("strategy is required");
        if (evidenceMaxAgeDays <= 0)
            throw new IllegalArgumentException("evidenceMaxAgeDays must be positive");
        frameworks = frameworks != null ? List.copyOf(frameworks) : List.of();
        properties = properties != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(properties))
                : Map.of();
    }

    @Override
    public boolean requiresHuman() {
        return requiresHumanReview();
    }
}
