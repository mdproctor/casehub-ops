package io.casehub.ops.compliance;

import java.util.List;

public record FrameworkPosture(
        String framework,
        int totalControls,
        int passingControls,
        int failingControls,
        int unavailableControls,
        int staleControls,
        int missingControls,
        List<ControlStatus> controls
) {
    public double complianceScore() {
        return totalControls == 0 ? 0.0 : (double) passingControls / totalControls;
    }
}
