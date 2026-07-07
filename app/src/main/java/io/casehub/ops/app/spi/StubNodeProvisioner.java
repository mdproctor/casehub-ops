package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Stub NodeProvisioner handling common K8s node types.
 * Yields to real implementations via {@code @DefaultBean}.
 */
@DefaultBean
@ApplicationScoped
public class StubNodeProvisioner implements NodeProvisioner {

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
                NodeType.of("k8s_namespace"),
                NodeType.of("k8s_deployment"),
                NodeType.of("k8s_service"),
                NodeType.of("k8s_ingress"),
                NodeType.of("k8s_configmap"));
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        return new ProvisionResult.Success();
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        return new DeprovisionResult.Success();
    }
}
