package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class LogRetentionEvidenceCollector implements EvidenceCollector {
    @Override
    public String controlType() {
        return "LOG_RETENTION";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: retention policy present");
    }
}
