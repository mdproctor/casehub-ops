package io.casehub.ops.infra.standalone;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.spi.ResourceProvisioner;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.task.ProvisionOutcome;
import io.casehub.ops.api.infra.task.ProvisionTask;
import io.smallrye.mutiny.Uni;

/**
 * In-memory {@link ResourceProvisioner} that stores state in a {@link ConcurrentHashMap}.
 *
 * <p>Handles all {@link InfraNodeSpec} types — acts as the default fallback provisioner
 * at {@code @Priority(0)}. Production provisioners (Terraform, Ansible) register at higher
 * priorities and handle only their specific spec types.
 *
 * <p>Useful for testing, demos, and validating the reconciliation loop without
 * real infrastructure.
 */
@ApplicationScoped
@Priority(0)
public class InMemoryResourceProvisioner implements ResourceProvisioner {

    private final ConcurrentHashMap<NodeId, ResourceState> store = new ConcurrentHashMap<>();

    @Override
    public String provisionerId() {
        return "in-memory";
    }

    @Override
    public boolean handles(InfraNodeSpec spec) {
        return true;
    }

    @Override
    public Uni<ProvisionOutcome> execute(ProvisionTask task) {
        return switch (task.action()) {
            case CREATE, UPDATE -> {
                var state = new ResourceState(
                        task.nodeId(),
                        task.spec().resourceType(),
                        ResourceStatus.HEALTHY,
                        Instant.now(),
                        null,
                        ResourceOutputs.empty());
                store.put(task.nodeId(), state);
                yield Uni.createFrom().item(new ProvisionOutcome(true, state, "provisioned", null));
            }
            case DESTROY -> {
                store.remove(task.nodeId());
                yield Uni.createFrom().item(new ProvisionOutcome(true, null, "destroyed", null));
            }
        };
    }

    /** Returns stored state for test verification. */
    public Optional<ResourceState> getState(NodeId nodeId) {
        return Optional.ofNullable(store.get(nodeId));
    }
}
