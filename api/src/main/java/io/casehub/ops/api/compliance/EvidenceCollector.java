package io.casehub.ops.api.compliance;

public interface EvidenceCollector {
    String controlType();
    EvidenceResult collect(ComplianceControlSpec spec, String tenancyId);
}
