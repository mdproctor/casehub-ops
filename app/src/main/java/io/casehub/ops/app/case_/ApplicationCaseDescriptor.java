package io.casehub.ops.app.case_;

import java.util.List;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.SubCase;

public final class ApplicationCaseDescriptor {

    private ApplicationCaseDescriptor() {}

    public static CaseDefinition build() {
        return CaseDefinition.builder()
                .namespace("ops")
                .name("application-lifecycle")
                .version("1.0")
                .title("Application Lifecycle")
                .summary("Long-lived case managing a deployed application")
                .bindings(bindings())
                .build();
    }

    private static List<Binding> bindings() {
        return List.of(
                childCaseBinding("on-drift-detected", ".driftDetected",
                        "ops", "drift-remediation", "1.0", ".driftDetected"),
                childCaseBinding("on-cve-detected", ".cveDetected",
                        "ops", "cve-response", "1.0", ".cveData"),
                childCaseBinding("on-upgrade-requested", ".upgradeRequested",
                        "ops", "service-upgrade", "1.0", ".upgradeSpec"),
                childCaseBinding("on-incident-detected", ".incidentDetected",
                        "ops", "incident-response", "1.0", ".incidentData"),
                childCaseBinding("on-scaling-required", ".scalingRequired",
                        "ops", "scaling-event", "1.0", ".scalingSpec"),
                childCaseBinding("on-compliance-violation", ".complianceViolation",
                        "ops", "compliance-remediation", "1.0", ".violationData"));
    }

    private static Binding childCaseBinding(String name, String triggerFilter,
                                             String childNs, String childName,
                                             String childVersion, String inputMapping) {
        return Binding.builder()
                .name(name)
                .on(new ContextChangeTrigger(triggerFilter))
                .subCase(SubCase.builder()
                        .namespace(childNs)
                        .name(childName)
                        .version(childVersion)
                        .inputMapping(inputMapping)
                        .waitForCompletion(false)
                        .build())
                .build();
    }
}
