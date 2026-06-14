package io.casehub.ops.api.infra.task;

import io.casehub.ops.api.infra.state.ResourceState;

public record ProvisionOutcome(
        boolean success,
        ResourceState resultState,
        String executionLog,
        ExecutionArtifact artifact) {

    // resultState, executionLog, artifact all nullable
}
