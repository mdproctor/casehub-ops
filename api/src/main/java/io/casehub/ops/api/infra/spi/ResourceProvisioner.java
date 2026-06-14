package io.casehub.ops.api.infra.spi;

import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.task.ProvisionOutcome;
import io.casehub.ops.api.infra.task.ProvisionTask;
import io.smallrye.mutiny.Uni;

/**
 * Task execution SPI for standalone backend only.
 * Handles individual resource provisioning tasks.
 */
public interface ResourceProvisioner {

    String provisionerId();

    boolean handles(InfraNodeSpec spec);

    Uni<ProvisionOutcome> execute(ProvisionTask task);
}
