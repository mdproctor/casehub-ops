package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record TerraformBackendConfig(TerraformStateType type, String bucket, String key) {

    public TerraformBackendConfig {
        Objects.requireNonNull(type, "type");
    }
}
