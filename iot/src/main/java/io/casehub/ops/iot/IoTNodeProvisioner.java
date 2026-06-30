package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class IoTNodeProvisioner implements NodeProvisioner {

    private static final String DISPATCHED_BY = "casehub-ops-iot-provisioner";

    private final DeviceRegistry registry;
    private final Map<String, DeviceProvider> providers;
    private final ApprovalEvaluator approvalEvaluator;
    private final PlanStore planStore;

    @Inject
    public IoTNodeProvisioner(DeviceRegistry registry,
                               @Any Instance<DeviceProvider> providerBeans,
                               ApprovalEvaluator approvalEvaluator,
                               PlanStore planStore) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerBeans.forEach(p -> providers.put(p.providerId(), p));
        this.approvalEvaluator = approvalEvaluator;
        this.planStore = planStore;
    }

    IoTNodeProvisioner(DeviceRegistry registry, List<DeviceProvider> providerList,
                        ApprovalEvaluator approvalEvaluator, PlanStore planStore) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerList.forEach(p -> providers.put(p.providerId(), p));
        this.approvalEvaluator = approvalEvaluator;
        this.planStore = planStore;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (context.hasApproval()) {
            return handleProvisionReEntry(node, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new ProvisionResult.PendingApproval(node.id(), ref);
        }

        return doProvision(node, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (context.hasApproval()) {
            return handleDeprovisionReEntry(node, context);
        }

        var decision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
        if (decision instanceof ApprovalDecision.RequiresApproval req) {
            String ref = planStore.store(req.plan());
            return new DeprovisionResult.PendingApproval(node.id(), ref);
        }

        return doDeprovision(node, context);
    }

    private ProvisionResult handleProvisionReEntry(DesiredNode node, ProvisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            // Plan expired or was removed — re-evaluate without approval
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.PROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new ProvisionResult.PendingApproval(node.id(), newRef);
            }
            return doProvision(node, context);
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
            return doProvision(node, context);
        }

        // Plan valid — execute and clean up
        ProvisionResult result = doProvision(node, context);
        if (result instanceof ProvisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private DeprovisionResult handleDeprovisionReEntry(DesiredNode node, DeprovisionContext context) {
        var planOpt = planStore.retrieve(context.approval().planReference());
        if (planOpt.isEmpty()) {
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node, context);
        }

        var plan = planOpt.get();
        if (!plan.originalSpec().equals(node.spec())) {
            planStore.remove(context.approval().planReference());
            var freshDecision = approvalEvaluator.evaluate(node, StepAction.DEPROVISION, context.tenancyId());
            if (freshDecision instanceof ApprovalDecision.RequiresApproval req) {
                String newRef = planStore.store(req.plan());
                return new DeprovisionResult.PendingApproval(node.id(), newRef);
            }
            return doDeprovision(node, context);
        }

        DeprovisionResult result = doDeprovision(node, context);
        if (result instanceof DeprovisionResult.Success) {
            planStore.remove(context.approval().planReference());
        }
        return result;
    }

    private ProvisionResult doProvision(DesiredNode node, ProvisionContext context) {
        return switch (node.spec()) {
            case PhysicalDeviceSpec s ->
                new ProvisionResult.Failed("physical devices cannot be auto-provisioned");
            case DeviceConfigSpec s -> provisionConfig(s);
            default -> new ProvisionResult.Failed("unknown spec type: " + node.spec().getClass());
        };
    }

    private DeprovisionResult doDeprovision(DesiredNode node, DeprovisionContext context) {
        return switch (node.spec()) {
            case PhysicalDeviceSpec s ->
                new DeprovisionResult.Failed("physical devices cannot be auto-deprovisioned");
            case DeviceConfigSpec s -> new DeprovisionResult.Success();
            default -> new DeprovisionResult.Failed("unknown spec type");
        };
    }

    private ProvisionResult provisionConfig(DeviceConfigSpec spec) {
        var optDevice = registry.findById(spec.deviceId());
        if (optDevice.isEmpty()) {
            return new ProvisionResult.Failed("device not present: " + spec.deviceId());
        }
        var device = optDevice.get();

        var provider = providers.get(device.providerId());
        if (provider == null) {
            return new ProvisionResult.Failed(
                "no provider for '" + device.providerId() + "'");
        }

        var actualNorm = CapabilityNormalizer.normalize(device.capabilities());
        var desiredNorm = CapabilityNormalizer.normalize(spec.desiredCapabilities());
        var ctx = new CapabilityCommandMapper.CommandContext(
            DISPATCHED_BY, UUID.randomUUID().toString());

        for (var entry : desiredNorm.entrySet()) {
            Object actualVal = actualNorm.get(entry.getKey());
            if (!entry.getValue().equals(actualVal)) {
                DeviceCommand cmd = CapabilityCommandMapper.toCommand(
                    spec.deviceId(), entry.getKey(),
                    spec.desiredCapabilities().get(entry.getKey()), ctx);
                CommandResult result = provider.dispatch(cmd).await().indefinitely();
                if (result != CommandResult.SENT) {
                    return new ProvisionResult.Failed(
                        "command " + cmd.action() + " returned " + result);
                }
            }
        }
        return new ProvisionResult.Success();
    }
}
