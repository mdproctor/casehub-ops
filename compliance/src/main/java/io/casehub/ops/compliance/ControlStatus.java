package io.casehub.ops.compliance;

import io.casehub.ops.api.compliance.EvidenceOutcome;
import java.time.Instant;

public record ControlStatus(
        String controlId,
        String controlType,
        String requirement,
        EvidenceOutcome lastOutcome,
        Instant lastEvidenceAt,
        boolean stale
) {}
