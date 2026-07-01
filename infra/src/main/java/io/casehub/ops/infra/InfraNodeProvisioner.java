package io.casehub.ops.infra;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;
import io.casehub.ops.api.approval.ApprovalThresholds;
import io.casehub.ops.api.approval.PlanStore;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.context.ProvisionAction;
import io.casehub.ops.api.infra.context.ProvisionPhase;
import io.casehub.ops.api.infra.plan.ProvisionPlan;
import io.casehub.ops.api.infra.spi.BackendDeprovisionResult;
import io.casehub.ops.api.infra.spi.BackendProvisionResult;
import io.casehub.ops.api.infra.spi.InfraBackend;

/**
 * Dispatches provisioning/deprovisioning to the correct {@link InfraBackend}
 * based on the {@code backendId} encoded in each node's {@link InfraDesiredNodeSpec}.
 *
 * <p>This is the bridge between the generic desiredstate runtime (which calls
 * {@link NodeProvisioner}) and the infra-domain backends (Terraform, Ansible, standalone).
 *
 * <p>The runtime's {@code NodeProvisioner} is blocking. The internal {@code InfraBackend}
 * returns {@code Uni<T>}. This class bridges via {@code await().indefinitely()} — it must
 * be called from a worker thread, never from the Vert.x event loop.
 *
 * <p>Supports plan/apply lifecycle: first call evaluates approval via
 * {@link ApprovalEvaluator}. If approval is required, returns {@code PendingApproval}.
 * On re-entry with an approved plan, extracts the {@link ProvisionPlan} and passes it
 * to the backend in APPLY phase.
 */
@ApplicationScoped
public class InfraNodeProvisioner implements NodeProvisioner {

    private static final ApprovalThresholds DEFAULT_THRESHOLDS =
            new ApprovalThresholds(RiskClassification.LOW);

    private final Map<String, InfraBackend> backends;
    private final ApprovalEvaluator approvalEvaluator;
    private final PlanStore planStore;

    @Inject
    public InfraNodeProvisioner(@Any Instance<InfraBackend> backends,
                                ApprovalEvaluator approvalEvaluator,
                                PlanStore planStore) {
        this.backends = backends.stream()
                .collect(Collectors.toMap(InfraBackend::backendId, b -> b));
        this.approvalEvaluator = approvalEvaluator;
        this.planStore = planStore;
    }

    /** Test constructor — accepts explicit backends, evaluator, and plan store. */
    InfraNodeProvisioner(List<InfraBackend> backends,
                         ApprovalEvaluator approvalEvaluator,
                         PlanStore planStore) {
        this.backends = backends.stream()
                .collect(Collectors.toMap(InfraBackend::backendId, b -> b));
        this.approvalEvaluator = approvalEvaluator;
        this.planStore = planStore;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ProvisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }

        var backend = backends.get(wrapper.backendId());
        if (backend == null) {
            return new ProvisionResult.Failed("No backend found for backendId: " + wrapper.backendId());
        }

        if (context.hasApproval()) {
            return handleProvisionReEntry(node, wrapper, backend, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), ref);
        }

        return doProvision(node.id(), wrapper, backend, null, context.tenancyId());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new DeprovisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }

        var backend = backends.get(wrapper.backendId());
        if (backend == null) {
            return new DeprovisionResult.Failed("No backend found for backendId: " + wrapper.backendId());
        }

        if (context.hasApproval()) {
            return handleDeprovisionReEntry(node, wrapper, backend, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), ref);
        }

        return doDeprovision(node.id(), wrapper, backend, null, context.tenancyId());
    }

    private ProvisionResult handleProvisionReEntry(DesiredNode node, InfraDesiredNodeSpec wrapper,
                                                    InfraBackend backend, ProvisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node.id(), wrapper, backend, null, context.tenancyId());
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node.id(), wrapper, backend, null, context.tenancyId());
        }

        ProvisionPlan infraPlan = plan.detail() instanceof ProvisionPlan p ? p : null;
        ProvisionResult result = doProvision(node.id(), wrapper, backend, infraPlan, context.tenancyId());
        if (result instanceof ProvisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private DeprovisionResult handleDeprovisionReEntry(DesiredNode node, InfraDesiredNodeSpec wrapper,
                                                        InfraBackend backend, DeprovisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node.id(), wrapper, backend, null, context.tenancyId());
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node.id(), wrapper, backend, null, context.tenancyId());
        }

        ProvisionPlan infraPlan = plan.detail() instanceof ProvisionPlan p ? p : null;
        DeprovisionResult result = doDeprovision(node.id(), wrapper, backend, infraPlan, context.tenancyId());
        if (result instanceof DeprovisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private ProvisionResult doProvision(NodeId nodeId, InfraDesiredNodeSpec wrapper,
                                         InfraBackend backend, ProvisionPlan approvedPlan,
                                         String tenancyId) {
        var infraCtx = new InfraProvisionContext(
                nodeId, tenancyId,
                ProvisionPhase.APPLY,
                ProvisionAction.PROVISION,
                approvedPlan,
                DEFAULT_THRESHOLDS,
                Instant.now());

        BackendProvisionResult backendResult = backend.provision(wrapper.resourceSpec(), infraCtx)
                .await().indefinitely();
        return mapProvisionResult(backendResult);
    }

    private DeprovisionResult doDeprovision(NodeId nodeId, InfraDesiredNodeSpec wrapper,
                                             InfraBackend backend, ProvisionPlan approvedPlan,
                                             String tenancyId) {
        var infraCtx = new InfraProvisionContext(
                nodeId, tenancyId,
                ProvisionPhase.APPLY,
                ProvisionAction.DEPROVISION,
                approvedPlan,
                DEFAULT_THRESHOLDS,
                Instant.now());

        BackendDeprovisionResult backendResult = backend.deprovision(wrapper.resourceSpec(), infraCtx)
                .await().indefinitely();
        return mapDeprovisionResult(backendResult);
    }

    private ProvisionResult mapProvisionResult(BackendProvisionResult result) {
        return switch (result) {
            case BackendProvisionResult.Provisioned p -> new ProvisionResult.Success();
            case BackendProvisionResult.Failed f -> new ProvisionResult.Failed(f.reason());
        };
    }

    private DeprovisionResult mapDeprovisionResult(BackendDeprovisionResult result) {
        return switch (result) {
            case BackendDeprovisionResult.Deprovisioned d -> new DeprovisionResult.Success();
            case BackendDeprovisionResult.Failed f -> new DeprovisionResult.Failed(f.reason());
        };
    }
}
