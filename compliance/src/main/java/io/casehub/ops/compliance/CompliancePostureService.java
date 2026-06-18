package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import io.casehub.ops.api.compliance.EvidenceOutcome;
import io.casehub.ops.api.compliance.FrameworkMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class CompliancePostureService {

    private final ComplianceFrameworkRegistry registry;
    private final ComplianceEvidenceService evidenceService;

    @Inject
    public CompliancePostureService(
            ComplianceFrameworkRegistry registry,
            ComplianceEvidenceService evidenceService) {
        this.registry = registry;
        this.evidenceService = evidenceService;
    }

    public FrameworkPosture postureFor(String framework, String tenancyId) {
        List<ComplianceControlSpec> controls = registry.controlsForFramework(framework);
        if (controls.isEmpty()) {
            return new FrameworkPosture(framework, 0, 0, 0, 0, 0, 0, List.of());
        }

        int passing = 0, failing = 0, unavailable = 0, stale = 0, missing = 0;
        List<ControlStatus> statuses = new ArrayList<>();

        for (var spec : controls) {
            ControlEvidenceStatus evidence = evidenceService.evidenceStatus(spec, tenancyId);
            String requirement = spec.frameworks().stream()
                    .filter(fm -> fm.framework().equals(framework))
                    .map(FrameworkMapping::requirement)
                    .findFirst().orElse("");

            statuses.add(new ControlStatus(
                    spec.controlId(), spec.controlType(), requirement,
                    evidence.latestOutcome(), evidence.latestEvidenceAt(), evidence.stale()));

            switch (evidence.derivedNodeStatus()) {
                case PRESENT -> passing++;
                case ABSENT -> missing++;
                case DRIFTED -> {
                    if (evidence.stale()) stale++;
                    else if (evidence.latestOutcome() == EvidenceOutcome.UNAVAILABLE) unavailable++;
                    else failing++;
                }
                case UNKNOWN -> missing++;
            }
        }

        return new FrameworkPosture(framework, controls.size(),
                passing, failing, unavailable, stale, missing, statuses);
    }

    public Map<String, FrameworkPosture> postureForAll(String tenancyId) {
        Map<String, FrameworkPosture> result = new LinkedHashMap<>();
        for (String framework : registry.registeredFrameworks()) {
            result.put(framework, postureFor(framework, tenancyId));
        }
        return result;
    }
}
