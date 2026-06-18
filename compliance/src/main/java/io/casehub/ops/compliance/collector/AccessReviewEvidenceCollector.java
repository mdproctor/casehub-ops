package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AccessReviewEvidenceCollector implements EvidenceCollector {
    @Override
    public String controlType() {
        return "ACCESS_REVIEW";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: access review completed");
    }
}
