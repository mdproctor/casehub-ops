package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record IngressRule(String path, String serviceName, int servicePort) {

    public IngressRule {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(serviceName, "serviceName");
    }
}
