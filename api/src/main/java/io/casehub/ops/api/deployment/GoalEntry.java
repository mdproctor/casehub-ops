package io.casehub.ops.api.deployment;

import java.util.List;

public record GoalEntry<S extends DeploymentNodeSpec>(S spec, List<String> dependsOn) {
    public GoalEntry {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
