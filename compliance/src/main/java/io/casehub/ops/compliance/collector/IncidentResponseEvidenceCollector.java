package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IncidentResponseEvidenceCollector implements EvidenceCollector {
    @Override
    public String controlType() {
        return "INCIDENT_RESPONSE";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: playbook verified");
    }
}
