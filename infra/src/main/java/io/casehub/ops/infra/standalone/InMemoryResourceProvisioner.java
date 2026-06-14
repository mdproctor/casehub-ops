package io.casehub.ops.infra.standalone;

import java.time.Instant;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.spi.ResourceProvisioner;
import io.casehub.ops.api.infra.state.ResourceOutputs;
import io.casehub.ops.api.infra.state.ResourceState;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.task.ProvisionOutcome;
import io.casehub.ops.api.infra.task.ProvisionTask;
import io.smallrye.mutiny.Uni;

/**
 * Default fallback {@link ResourceProvisioner} at {@code @Priority(0)}.
 * Handles all {@link InfraNodeSpec} types. Production provisioners register
 * at higher priorities for specific spec types.
 *
 * <p>State is managed by the owning {@link StandaloneBackend}, not here.
 */
@ApplicationScoped
@Priority(0)
public class InMemoryResourceProvisioner implements ResourceProvisioner {

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
                yield Uni.createFrom().item(new ProvisionOutcome(true, state, "provisioned", null));
            }
            case DESTROY -> Uni.createFrom().item(new ProvisionOutcome(true, null, "destroyed", null));
        };
    }
}
