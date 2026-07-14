package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.app.goal.ApplicationNodeTypes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Set;

@ApplicationScoped
public class KubernetesNodeProvisioner implements NodeProvisioner {

    private final K8sHandlerRegistry handlerRegistry;
    private final K8sClientRegistry  clientRegistry;

    @Inject
    public KubernetesNodeProvisioner(K8sHandlerRegistry handlerRegistry,
                                     K8sClientRegistry clientRegistry) {
        this.handlerRegistry = handlerRegistry;
        this.clientRegistry  = clientRegistry;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(
                ApplicationNodeTypes.K8S_NAMESPACE,
                ApplicationNodeTypes.K8S_DEPLOYMENT,
                ApplicationNodeTypes.K8S_SERVICE,
                ApplicationNodeTypes.K8S_INGRESS,
                ApplicationNodeTypes.K8S_CONFIGMAP);
    }

    @Override
    public Duration resyncInterval() {
        return Duration.ofMinutes(5);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new ProvisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }
        try {
            String        clusterId    = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            @SuppressWarnings("unchecked")
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            clientRegistry.withRetryOn401(clusterId, client -> {
                handler.apply(client, resourceSpec);
                return null;
            });
            return new ProvisionResult.Success();
        } catch (Exception e) {
            return new ProvisionResult.Failed(e.getMessage());
        }
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        if (!(node.spec() instanceof InfraDesiredNodeSpec wrapper)) {
            return new DeprovisionResult.Failed("spec is not InfraDesiredNodeSpec");
        }
        try {
            String        clusterId    = KubernetesActualStateAdapter.extractClusterId(wrapper.backendId());
            InfraNodeSpec resourceSpec = wrapper.resourceSpec();
            @SuppressWarnings("unchecked")
            var handler = (K8sResourceHandler<InfraNodeSpec>) handlerRegistry.handlerFor(resourceSpec.getClass());
            clientRegistry.withRetryOn401(clusterId, client -> {
                handler.delete(client, resourceSpec);
                return null;
            });
            return new DeprovisionResult.Success();
        } catch (Exception e) {
            return new DeprovisionResult.Failed(e.getMessage());
        }
    }
}
