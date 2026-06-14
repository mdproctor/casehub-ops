package io.casehub.ops.api.infra;

import java.util.Objects;

import io.casehub.ops.api.infra.types.TerraformBackendConfig;

public record TerraformWorkspaceSpec(String workspacePath, TerraformBackendConfig state) implements InfraNodeSpec {

    public TerraformWorkspaceSpec {
        Objects.requireNonNull(workspacePath, "workspacePath");
        Objects.requireNonNull(state, "state");
    }

    @Override
    public String resourceType() {
        return "terraform_workspace";
    }
}
