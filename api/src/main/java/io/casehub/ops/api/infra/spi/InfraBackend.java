package io.casehub.ops.api.infra.spi;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.plan.ProvisionPlan;
import io.casehub.ops.api.infra.state.DriftReport;
import io.casehub.ops.api.infra.state.ResourceState;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

/**
 * Reactive SPI for infrastructure backends (Terraform, Ansible, standalone).
 * Takes domain types (InfraNodeSpec, InfraProvisionContext) — NOT desiredstate runtime types.
 */
public interface InfraBackend {

    String backendId();

    Uni<BackendProvisionResult> provision(InfraNodeSpec spec, InfraProvisionContext context);

    Uni<BackendDeprovisionResult> deprovision(InfraNodeSpec spec, InfraProvisionContext context);

    Uni<ResourceState> readState(NodeId nodeId, InfraNodeSpec spec);

    Uni<DriftReport> detectDrift(NodeId nodeId, InfraNodeSpec spec);

    Uni<Optional<ProvisionPlan>> plan(InfraNodeSpec spec, InfraProvisionContext context);
}
