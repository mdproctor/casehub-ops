package io.casehub.ops.infra.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.GenericResourceSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.state.ResourceStatus;
import io.casehub.ops.api.infra.task.ProvisionTask;
import io.casehub.ops.api.infra.task.TaskAction;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;

class InMemoryResourceProvisionerTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");

    private final InMemoryResourceProvisioner provisioner = new InMemoryResourceProvisioner();

    @Test
    void handlesAllSpecs() {
        assertThat(provisioner.handles(new K8sNamespaceSpec("ns", Labels.empty()))).isTrue();
        assertThat(provisioner.handles(new K8sDeploymentSpec(
                "ns", "app", "img:1", 1,
                new ResourceRequirements("100m", "200m", "128Mi", "256Mi"),
                Labels.empty()))).isTrue();
        assertThat(provisioner.handles(new GenericResourceSpec(
                "custom", JsonNodeFactory.instance.objectNode()))).isTrue();
    }

    @Test
    void createReturnsHealthyState() {
        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var task = new ProvisionTask(NODE_1, spec, TaskAction.CREATE, null);

        var outcome = provisioner.execute(task).await().indefinitely();

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.resultState()).isNotNull();
        assertThat(outcome.resultState().nodeId()).isEqualTo(NODE_1);
        assertThat(outcome.resultState().status()).isEqualTo(ResourceStatus.HEALTHY);
        assertThat(outcome.executionLog()).isNotBlank();
    }

    @Test
    void updateReturnsNewState() {
        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var createTask = new ProvisionTask(NODE_1, spec, TaskAction.CREATE, null);
        var createOutcome = provisioner.execute(createTask).await().indefinitely();

        var updateSpec = new K8sNamespaceSpec("production-v2", Labels.empty());
        var updateTask = new ProvisionTask(NODE_1, updateSpec, TaskAction.UPDATE, createOutcome.resultState());

        var outcome = provisioner.execute(updateTask).await().indefinitely();

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.resultState().nodeId()).isEqualTo(NODE_1);
        assertThat(outcome.resultState().status()).isEqualTo(ResourceStatus.HEALTHY);
    }

    @Test
    void destroyReturnsSuccessWithNullState() {
        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var createTask = new ProvisionTask(NODE_1, spec, TaskAction.CREATE, null);
        var createOutcome = provisioner.execute(createTask).await().indefinitely();

        var destroyTask = new ProvisionTask(NODE_1, spec, TaskAction.DESTROY, createOutcome.resultState());

        var outcome = provisioner.execute(destroyTask).await().indefinitely();

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.resultState()).isNull();
    }

    @Test
    void executeReturnsCorrectResourceType() {
        var spec = new K8sNamespaceSpec("production", Labels.empty());
        var task = new ProvisionTask(NODE_1, spec, TaskAction.CREATE, null);

        var outcome = provisioner.execute(task).await().indefinitely();

        assertThat(outcome.resultState().resourceType()).isEqualTo("k8s_namespace");
    }
}
