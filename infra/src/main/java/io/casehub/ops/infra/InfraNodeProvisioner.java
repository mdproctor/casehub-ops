package io.casehub.ops.infra;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.context.ProvisionAction;
import io.casehub.ops.api.infra.context.ProvisionPhase;
import io.casehub.ops.api.infra.context.RiskClassification;
import io.casehub.ops.api.infra.context.RiskThresholds;
import io.casehub.ops.api.infra.spi.BackendDeprovisionResult;
import io.casehub.ops.api.infra.spi.BackendProvisionResult;
import io.casehub.ops.api.infra.spi.InfraBackend;
import jakarta.inject.Inject;

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
 * <p>TODO: plan/apply lifecycle — the runtime does not yet support PendingApproval callbacks,
 * so we always provision directly (APPLY phase).
 */
@ApplicationScoped
public class InfraNodeProvisioner implements NodeProvisioner {

    private static final RiskThresholds DEFAULT_THRESHOLDS =
            new RiskThresholds(RiskClassification.LOW, false);

    private final Map<String, InfraBackend> backends;

    @Inject
    public InfraNodeProvisioner(@Any Instance<InfraBackend> backends) {
        this.backends = backends.stream()
                .collect(Collectors.toMap(InfraBackend::backendId, b -> b));
    }

    /** Test constructor — accepts an explicit list of backends. */
    InfraNodeProvisioner(List<InfraBackend> backends) {
        this.backends = backends.stream()
                .collect(Collectors.toMap(InfraBackend::backendId, b -> b));
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

        var infraCtx = new InfraProvisionContext(
                node.id(),
                context.tenancyId(),
                ProvisionPhase.APPLY,
                ProvisionAction.PROVISION,
                null,
                DEFAULT_THRESHOLDS,
                Instant.now());

        BackendProvisionResult backendResult = backend.provision(wrapper.resourceSpec(), infraCtx)
                .await().indefinitely();
        return mapProvisionResult(backendResult);
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

        var infraCtx = new InfraProvisionContext(
                node.id(),
                context.tenancyId(),
                ProvisionPhase.APPLY,
                ProvisionAction.DEPROVISION,
                null,
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
