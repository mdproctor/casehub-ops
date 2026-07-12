package io.casehub.ops.infra.standalone;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.approval.ApprovalThresholds;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.context.InfraProvisionContext;
import io.casehub.ops.api.infra.context.ProvisionAction;
import io.casehub.ops.api.infra.context.ProvisionPhase;
import io.casehub.ops.api.infra.spi.BackendDeprovisionResult;
import io.casehub.ops.api.infra.spi.BackendProvisionResult;
import io.casehub.ops.api.infra.spi.ResourceProvisioner;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.types.Labels;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneBackendTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final ApprovalThresholds THRESHOLDS = new ApprovalThresholds(RiskClassification.LOW);

    private InfraProvisionContext provisionContext(NodeId nodeId) {
        return new InfraProvisionContext(
                nodeId, "tenant-1", ProvisionPhase.APPLY, ProvisionAction.PROVISION,
                null, THRESHOLDS, Instant.now());
    }

    private InfraProvisionContext deprovisionContext(NodeId nodeId) {
        return new InfraProvisionContext(
                nodeId, "tenant-1", ProvisionPhase.APPLY, ProvisionAction.DEPROVISION,
                null, THRESHOLDS, Instant.now());
    }

    @Test
    void provisionDelegatesToProvisioner() {
        var provisioner = new InMemoryResourceProvisioner();
        var backend = new StandaloneBackend(List.of(provisioner));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var result = backend.provision(spec, provisionContext(NODE_1))
                .await().indefinitely();

        assertThat(result).isInstanceOf(BackendProvisionResult.Provisioned.class);
        var provisioned = (BackendProvisionResult.Provisioned) result;
        assertThat(provisioned.state().nodeId()).isEqualTo(NODE_1);
        assertThat(provisioned.state().resourceType()).isEqualTo("k8s_namespace");
        assertThat(provisioned.state().status()).isEqualTo(ResourceStatus.HEALTHY);
    }

    @Test
    void deprovisionDelegatesToProvisioner() {
        var provisioner = new InMemoryResourceProvisioner();
        var backend = new StandaloneBackend(List.of(provisioner));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        // first provision
        backend.provision(spec, provisionContext(NODE_1)).await().indefinitely();

        // then deprovision
        var result = backend.deprovision(spec, deprovisionContext(NODE_1))
                .await().indefinitely();

        assertThat(result).isInstanceOf(BackendDeprovisionResult.Deprovisioned.class);
        assertThat(((BackendDeprovisionResult.Deprovisioned) result).nodeId()).isEqualTo(NODE_1);
    }

    @Test
    void readStateReturnsStoredState() {
        var provisioner = new InMemoryResourceProvisioner();
        var backend     = new StandaloneBackend(List.of(provisioner));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        backend.provision(spec, provisionContext(NODE_1)).await().indefinitely();

        var state = backend.readState(NODE_1, spec).await().indefinitely();

        assertThat(state.nodeId()).isEqualTo(NODE_1);
        assertThat(state.status()).isEqualTo(ResourceStatus.HEALTHY);
        assertThat(state.resourceType()).isEqualTo("k8s_namespace");
    }

    @Test
    void readStateReturnsUnknownWhenNotProvisioned() {
        var provisioner = new InMemoryResourceProvisioner();
        var backend     = new StandaloneBackend(List.of(provisioner));

        var spec  = new K8sNamespaceSpec("staging", Labels.empty());
        var state = backend.readState(NODE_1, spec).await().indefinitely();

        assertThat(state.nodeId()).isEqualTo(NODE_1);
        assertThat(state.status()).isEqualTo(ResourceStatus.UNKNOWN);
    }

    @Test
    void provisionFailsWhenNoProvisionerHandlesSpec() {
        // empty provisioner list — nothing handles the spec
        List<ResourceProvisioner> empty = List.of();
        var backend = new StandaloneBackend(empty);

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var result = backend.provision(spec, provisionContext(NODE_1))
                .await().indefinitely();

        assertThat(result).isInstanceOf(BackendProvisionResult.Failed.class);
        var failed = (BackendProvisionResult.Failed) result;
        assertThat(failed.reason()).contains("No provisioner");
        assertThat(failed.retryable()).isFalse();
    }

    @Test
    void detectDriftReturnsNoDrift() {
        var provisioner = new InMemoryResourceProvisioner();
        var backend     = new StandaloneBackend(List.of(provisioner));

        var spec = new K8sNamespaceSpec("production", Labels.empty());
        backend.provision(spec, provisionContext(NODE_1)).await().indefinitely();

        var drift = backend.detectDrift(NODE_1, spec).await().indefinitely();

        assertThat(drift.nodeId()).isEqualTo(NODE_1);
        assertThat(drift.drifted()).isFalse();
        assertThat(drift.drifts()).isEmpty();
        assertThat(drift.backendId()).isEqualTo("standalone");
    }
}
