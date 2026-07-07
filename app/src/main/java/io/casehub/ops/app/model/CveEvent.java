package io.casehub.ops.app.model;

import java.util.List;
import java.util.Objects;

public record CveEvent(
        String cveId,
        CveSeverity severity,
        String affectedImage,
        List<String> affectedServices,
        String fixedInTag,
        String source) {

    public CveEvent {
        Objects.requireNonNull(cveId, "cveId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(affectedImage, "affectedImage");
        Objects.requireNonNull(affectedServices, "affectedServices");
        affectedServices = List.copyOf(affectedServices);
        Objects.requireNonNull(source, "source");
    }
}
