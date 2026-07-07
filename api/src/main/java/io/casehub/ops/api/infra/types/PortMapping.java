package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record PortMapping(int containerPort, int servicePort, String protocol) {
    public PortMapping {
        Objects.requireNonNull(protocol, "protocol");
    }
}
