package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DataProcessingEvidenceCollector implements EvidenceCollector {
    @Override
    public String controlType() {
        return "DATA_PROCESSING";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: processing records complete");
    }
}
