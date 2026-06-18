package io.casehub.ops.api.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FrameworkMapping(String framework, String requirement) {
    public FrameworkMapping {
        if (framework == null || framework.isBlank())
            throw new IllegalArgumentException("framework is required");
        if (requirement == null || requirement.isBlank())
            throw new IllegalArgumentException("requirement is required");
    }
}
