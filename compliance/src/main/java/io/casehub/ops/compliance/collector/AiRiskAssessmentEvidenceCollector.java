package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AiRiskAssessmentEvidenceCollector implements EvidenceCollector {
    @Override
    public String controlType() {
        return "AI_RISK_ASSESSMENT";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: risk assessment current");
    }
}
