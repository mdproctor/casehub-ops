package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record AnsibleInventory(String path, String hostGroup) {

    public AnsibleInventory {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(hostGroup, "hostGroup");
    }
}
