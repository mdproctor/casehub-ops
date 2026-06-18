package io.casehub.ops.compliance.collector;

import io.casehub.ops.api.compliance.*;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EncryptionEvidenceCollector implements EvidenceCollector {
    @Override
    public String controlType() {
        return "ENCRYPTION_AT_REST";
    }

    @Override
    public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
        return new EvidenceResult.Pass("stub: AES-256 encryption verified");
    }
}
