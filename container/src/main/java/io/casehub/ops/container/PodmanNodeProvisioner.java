package io.casehub.ops.container;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.ops.container.podman.PodmanClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class PodmanNodeProvisioner implements NodeProvisioner {

    private static final Set<NodeType> HANDLED = Set.of(
        ContainerNodeTypes.APP_CONTAINER, ContainerNodeTypes.DATABASE,
        ContainerNodeTypes.NETWORK, ContainerNodeTypes.VOLUME);

    private final PodmanClient podmanClient;

    @Inject
    public PodmanNodeProvisioner(PodmanClient podmanClient) {
        this.podmanClient = podmanClient;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return HANDLED;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        try {
            switch (node.spec()) {
                case AppContainerSpec spec -> provisionContainer(spec);
                case DatabaseContainerSpec spec -> provisionDatabase(spec);
                case NetworkSpec spec -> podmanClient.createNetwork(spec.name()).await().indefinitely();
                case VolumeSpec spec -> podmanClient.createVolume(spec.name(), spec.driver()).await().indefinitely();
                default -> { return new ProvisionResult.Failed("Unknown spec type: " + node.spec().getClass()); }
            }
            return new ProvisionResult.Success();
        } catch (Exception e) {
            return new ProvisionResult.Failed(e.getMessage());
        }
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        try {
            switch (node.spec()) {
                case AppContainerSpec spec -> deprovisionContainer(spec.name());
                case DatabaseContainerSpec spec -> deprovisionContainer(spec.name());
                case NetworkSpec spec -> { /* network cleanup deferred */ }
                case VolumeSpec spec -> { /* volume cleanup deferred */ }
                default -> { return new DeprovisionResult.Failed("Unknown spec type: " + node.spec().getClass()); }
            }
            return new DeprovisionResult.Success();
        } catch (Exception e) {
            return new DeprovisionResult.Failed(e.getMessage());
        }
    }

    private void provisionContainer(AppContainerSpec spec) {
        podmanClient.pullImage(spec.image()).await().indefinitely();
        var id = podmanClient.createContainer(new PodmanClient.CreateContainerRequest(
            spec.name(), spec.image(), spec.ports(), spec.env()
        )).await().indefinitely();
        podmanClient.startContainer(id).await().indefinitely();
    }

    private void provisionDatabase(DatabaseContainerSpec spec) {
        podmanClient.pullImage(spec.image()).await().indefinitely();
        var env = Map.of(
            "POSTGRES_DB", spec.databaseName(),
            "POSTGRES_USER", spec.username(),
            "POSTGRES_PASSWORD", spec.password());
        var id = podmanClient.createContainer(new PodmanClient.CreateContainerRequest(
            spec.name(), spec.image(), Map.of(5432, 5432), env
        )).await().indefinitely();
        podmanClient.startContainer(id).await().indefinitely();
    }

    private void deprovisionContainer(String name) {
        var containers = podmanClient.listContainers().await().indefinitely();
        for (var c : containers) {
            if (c.names().contains(name)) {
                podmanClient.stopContainer(c.id()).await().indefinitely();
                podmanClient.removeContainer(c.id()).await().indefinitely();
                return;
            }
        }
    }
}
