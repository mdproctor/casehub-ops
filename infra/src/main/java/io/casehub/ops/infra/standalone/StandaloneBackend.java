package io.casehub.ops.infra.standalone;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.plan.ProvisionPlan;
import io.casehub.ops.api.infra.spi.BackendDeprovisionResult;
import io.casehub.ops.api.infra.spi.BackendProvisionResult;
import io.casehub.ops.api.infra.spi.InfraBackend;
import io.casehub.ops.api.infra.spi.ResourceProvisioner;
import io.casehub.ops.api.infra.state.DriftReport;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.task.ProvisionOutcome;
import io.casehub.ops.api.infra.task.ProvisionTask;
import io.casehub.ops.api.infra.task.TaskAction;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

/**
 * CaseHub-native provisioning backend. Delegates task execution to
 * {@link ResourceProvisioner} implementations discovered via CDI, tracking
 * state in a {@link ConcurrentHashMap}.
 *
 * <p>This backend provides the "standalone" provisioning path — no external
 * tools (Terraform, Ansible) required. The {@link InMemoryResourceProvisioner}
 * at {@code @Priority(0)} acts as the default catch-all; production provisioners
 * register at higher priorities for specific resource types.
 */
@ApplicationScoped
public class StandaloneBackend implements InfraBackend {

    private final List<ResourceProvisioner> provisioners;
    private final ConcurrentHashMap<NodeId, ResourceState> stateStore = new ConcurrentHashMap<>();

    @Inject
    public StandaloneBackend(@Any Instance<ResourceProvisioner> provisioners) {
        // CDI Instance iteration follows @Priority ordering (highest first)
        this.provisioners = provisioners.stream().toList();
    }

    /** Test constructor — accepts an explicit list of provisioners. */
    public StandaloneBackend(List<ResourceProvisioner> provisioners) {
        this.provisioners = List.copyOf(provisioners);
    }

    @Override
    public String backendId() {
        return "standalone";
    }

    @Override
    public Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context) {
        var provisioner = findProvisioner(spec);
        if (provisioner == null) {
            return Uni.createFrom().item(
                    new BackendProvisionResult.Failed(
                            "No provisioner handles resource type: " + spec.resourceType(), false));
        }

        var currentState = stateStore.get(context.nodeId());
        var action = currentState != null ? TaskAction.UPDATE : TaskAction.CREATE;
        var task = new ProvisionTask(context.nodeId(), spec, action, currentState);

        return provisioner.execute(task)
                .map(outcome -> mapProvisionOutcome(context.nodeId(), outcome));
    }

    @Override
    public Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context) {
        var provisioner = findProvisioner(spec);
        if (provisioner == null) {
            return Uni.createFrom().item(
                    new BackendDeprovisionResult.Failed(
                            "No provisioner handles resource type: " + spec.resourceType(), false));
        }

        var currentState = stateStore.get(context.nodeId());
        var task = new ProvisionTask(context.nodeId(), spec, TaskAction.DESTROY, currentState);

        return provisioner.execute(task)
                .map(outcome -> mapDeprovisionOutcome(context.nodeId(), outcome));
    }

    @Override
    public Uni<ResourceState> readState(NodeId nodeId) {
        var state = stateStore.get(nodeId);
        if (state != null) {
            return Uni.createFrom().item(state);
        }
        return Uni.createFrom().item(new ResourceState(
                nodeId, "unknown", ResourceStatus.UNKNOWN, Instant.now(), null, ResourceOutputs.empty()));
    }

    @Override
    public Uni<DriftReport> detectDrift(NodeId nodeId) {
        // PoC: in-memory state is always consistent — no external state to drift against
        return Uni.createFrom().item(new DriftReport(
                nodeId, false, List.of(), Instant.now(), backendId()));
    }

    @Override
    public Uni<Optional<ProvisionPlan>> plan(InfraNodeSpec spec, InfraProvisionContext context) {
        // PoC: plan generation requires diffing desired vs actual state — not yet implemented
        return Uni.createFrom().item(Optional.empty());
    }

    private ResourceProvisioner findProvisioner(InfraNodeSpec spec) {
        for (var provisioner : provisioners) {
            if (provisioner.handles(spec)) {
                return provisioner;
            }
        }
        return null;
    }

    private BackendProvisionResult mapProvisionOutcome(NodeId nodeId, ProvisionOutcome outcome) {
        if (outcome.success() && outcome.resultState() != null) {
            stateStore.put(nodeId, outcome.resultState());
            return new BackendProvisionResult.Provisioned(outcome.resultState());
        }
        return new BackendProvisionResult.Failed(
                outcome.executionLog() != null ? outcome.executionLog() : "provision failed", false);
    }

    private BackendDeprovisionResult mapDeprovisionOutcome(NodeId nodeId, ProvisionOutcome outcome) {
        if (outcome.success()) {
            stateStore.remove(nodeId);
            return new BackendDeprovisionResult.Deprovisioned(nodeId);
        }
        return new BackendDeprovisionResult.Failed(
                outcome.executionLog() != null ? outcome.executionLog() : "deprovision failed", false);
    }
}
