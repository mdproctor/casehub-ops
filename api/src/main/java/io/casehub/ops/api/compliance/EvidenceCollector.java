package io.casehub.ops.api.compliance;

public interface EvidenceCollector {
    String strategy();
    EvidenceResult collect(ComplianceControlSpec spec, String tenancyId);
}
