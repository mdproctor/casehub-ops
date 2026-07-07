package io.casehub.ops.app.model;

import java.util.Objects;

public record ServiceVersion(String serviceId, String image) {
    public ServiceVersion {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(image, "image");
    }
}
