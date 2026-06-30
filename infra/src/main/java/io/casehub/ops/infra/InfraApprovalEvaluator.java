package io.casehub.ops.infra;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.approval.ApprovalEvaluator;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.ApprovalThresholds;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.context.ProvisionAction;
import io.casehub.ops.api.infra.context.ProvisionPhase;
import io.casehub.ops.api.infra.plan.ProvisionPlan;
import io.casehub.ops.api.infra.spi.InfraBackend;

/**
 * Evaluates approval requirements for infra domain nodes by delegating to
 * {@link InfraBackend#plan()} to produce a {@link ProvisionPlan} and checking
 * its risk against configurable thresholds.
 *
 * <p>If the backend returns an empty plan (no changes needed), or the plan's
 * risk is below the approval threshold, the node is auto-approved.
 */
@ApplicationScoped
public class InfraApprovalEvaluator implements ApprovalEvaluator {

    private static final ApprovalThresholds DEFAULT_THRESHOLDS =
            new ApprovalThresholds(RiskClassification.HIGH);

    private final Map<String, InfraBackend> backends;
    private final ApprovalThresholds thresholds;

    @Inject
    public InfraApprovalEvaluator(@Any Instance<InfraBackend> backends) {
        this(backends.stream().collect(Collectors.toMap(InfraBackend::backendId, b -> b)),
                DEFAULT_THRESHOLDS);
    }

    /** Test constructor — accepts an explicit list of backends and thresholds. */
    InfraApprovalEvaluator(List<InfraBackend> backends, ApprovalThresholds thresholds) {
        this(backends.stream().collect(Collectors.toMap(InfraBackend::backendId, b -> b)),
                thresholds);
    }

    private InfraApprovalEvaluator(Map<String, InfraBackend> backends, ApprovalThresholds thresholds) {
        this.backends = backends;
        this.thresholds = thresholds;
    }

    @Override
    public ApprovalDecision evaluate(DesiredNode node, StepAction action, String tenancyId) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ApprovalDecision.AutoApproved();
        }

        var backend = backends.get(wrapper.backendId());
        if (backend == null) {
            return new ApprovalDecision.AutoApproved();
        }

        var planCtx = new InfraProvisionContext(
                node.id(),
                tenancyId,
                ProvisionPhase.PLAN,
                action == StepAction.DEPROVISION ? ProvisionAction.DEPROVISION : ProvisionAction.PROVISION,
                null,
                thresholds,
                Instant.now());

        Optional<ProvisionPlan> planOpt = backend.plan(wrapper.resourceSpec(), planCtx)
                .await().indefinitely();

        if (planOpt.isEmpty()) {
            return new ApprovalDecision.AutoApproved();
        }

        ProvisionPlan plan = planOpt.get();
        if (!thresholds.requiresApproval(plan.risk())) {
            return new ApprovalDecision.AutoApproved();
        }

        var approvalPlan = new ApprovalPlan(
                node.id(), action, plan.risk(),
                plan.summary(), tenancyId, node.spec(), plan);
        return new ApprovalDecision.RequiresApproval(approvalPlan);
    }
}
