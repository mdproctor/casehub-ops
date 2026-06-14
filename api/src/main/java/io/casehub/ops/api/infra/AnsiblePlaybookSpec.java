package io.casehub.ops.api.infra;

import java.util.Objects;

import io.casehub.ops.api.infra.types.AnsibleExtraVars;
import io.casehub.ops.api.infra.types.AnsibleInventory;

public record AnsiblePlaybookSpec(
        String playbookPath,
        AnsibleInventory inventory,
        AnsibleExtraVars extraVars) implements InfraNodeSpec {

    public AnsiblePlaybookSpec {
        Objects.requireNonNull(playbookPath, "playbookPath");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(extraVars, "extraVars");
    }

    @Override
    public String resourceType() {
        return "ansible_playbook";
    }
}
