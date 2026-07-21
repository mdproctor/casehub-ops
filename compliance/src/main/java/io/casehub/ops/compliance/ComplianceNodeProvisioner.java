package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;
import io.casehub.ops.api.approval.PlanStore;
import io.casehub.ops.api.compliance.ComplianceControlSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class ComplianceNodeProvisioner implements NodeProvisioner {

    static final Set<NodeType> HANDLED_TYPES = Set.of(
            NodeType.of("LOG_RETENTION"),
            NodeType.of("ENCRYPTION_AT_REST"),
            NodeType.of("ACCESS_REVIEW"),
            NodeType.of("INCIDENT_RESPONSE"),
            NodeType.of("DATA_PROCESSING"),
            NodeType.of("AI_RISK_ASSESSMENT"),
            NodeType.of("CERTIFICATE_EXPIRY"),
            NodeType.of("CONFIG_HASH"));

    private final ComplianceEvidenceService   evidenceService;
    private final ComplianceFrameworkRegistry registry;
    private final ComplianceSpecHashStore     specHashStore;
    private final ApprovalEvaluator           approvalEvaluator;
    private final PlanStore                   planStore;

    @Inject
    public ComplianceNodeProvisioner(
            ComplianceEvidenceService evidenceService,
            ComplianceFrameworkRegistry registry,
            ComplianceSpecHashStore specHashStore,
            ApprovalEvaluator approvalEvaluator,
            PlanStore planStore) {
        this.evidenceService   = evidenceService;
        this.registry          = registry;
        this.specHashStore     = specHashStore;
        this.approvalEvaluator = approvalEvaluator;
        this.planStore         = planStore;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return HANDLED_TYPES;
    }

    @Override
    public java.time.Duration resyncInterval() {
        return java.time.Duration.ofHours(1);
    }


    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof ComplianceControlSpec spec)) {
            return new ProvisionResult.Failed("spec is not ComplianceControlSpec");
        }

        if (context.hasApproval()) {
            return handleProvisionReEntry(node, spec, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), ref);
        }

        return doProvision(node, spec, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof ComplianceControlSpec spec)) {
            return new DeprovisionResult.Failed("spec is not ComplianceControlSpec");
        }

        if (context.hasApproval()) {
            return handleDeprovisionReEntry(node, spec, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), ref);
        }

        return doDeprovision(node, spec, context);
    }

    private ProvisionResult handleProvisionReEntry(DesiredNode node, ComplianceControlSpec spec, ProvisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            // Plan expired or was removed — re-evaluate without approval
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node, spec, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            // Spec changed since approval — remove stale plan, re-evaluate
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node, spec, context);
        }

        // Plan valid — execute and clean up
        ProvisionResult result = doProvision(node, spec, context);
        if (result instanceof ProvisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private DeprovisionResult handleDeprovisionReEntry(DesiredNode node, ComplianceControlSpec spec, DeprovisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node, spec, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node, spec, context);
        }

        DeprovisionResult result = doDeprovision(node, spec, context);
        if (result instanceof DeprovisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private ProvisionResult doProvision(DesiredNode node, ComplianceControlSpec spec, ProvisionContext context) {
        evidenceService.collectAndRecord(spec, context.tenancyId());
        registry.register(spec);
        specHashStore.record(node.id(), node.spec());
        return new ProvisionResult.Success();
    }

    private DeprovisionResult doDeprovision(DesiredNode node, ComplianceControlSpec spec, DeprovisionContext context) {
        registry.deregister(spec.controlId());
        specHashStore.remove(node.id());
        return new DeprovisionResult.Success();
    }
}
