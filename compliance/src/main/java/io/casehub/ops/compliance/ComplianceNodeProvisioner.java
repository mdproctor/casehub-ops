package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ComplianceNodeProvisioner implements NodeProvisioner {

    private final ComplianceEvidenceService evidenceService;
    private final ComplianceFrameworkRegistry registry;
    private final ComplianceSpecHashStore specHashStore;

    @Inject
    public ComplianceNodeProvisioner(
            ComplianceEvidenceService evidenceService,
            ComplianceFrameworkRegistry registry,
            ComplianceSpecHashStore specHashStore) {
        this.evidenceService = evidenceService;
        this.registry = registry;
        this.specHashStore = specHashStore;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof ComplianceControlSpec spec)) {
            return new ProvisionResult.Failed("spec is not ComplianceControlSpec");
        }
        evidenceService.collectAndRecord(spec, context.tenancyId());
        registry.register(spec);
        specHashStore.record(node.id(), node.spec());
        return new ProvisionResult.Success();
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof ComplianceControlSpec spec)) {
            return new DeprovisionResult.Failed("spec is not ComplianceControlSpec");
        }
        registry.deregister(spec.controlId());
        specHashStore.remove(node.id());
        return new DeprovisionResult.Success();
    }
}
