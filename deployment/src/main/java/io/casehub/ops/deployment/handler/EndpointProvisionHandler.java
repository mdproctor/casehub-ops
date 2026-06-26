package io.casehub.ops.deployment.handler;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.path.Path;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class EndpointProvisionHandler {

    private final EndpointRegistry endpointRegistry;

    @Inject
    public EndpointProvisionHandler(EndpointRegistry endpointRegistry) {
        this.endpointRegistry = endpointRegistry;
    }

    public ProvisionResult provision(EndpointNodeSpec spec, ProvisionContext context) {
        endpointRegistry.register(spec.toDescriptor(context.tenancyId()));
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(EndpointNodeSpec spec, DeprovisionContext context) {
        endpointRegistry.deregister(Path.parse(spec.path()), context.tenancyId());
        return new DeprovisionResult.Success();
    }
}
