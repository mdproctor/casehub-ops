package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.runtime.DefaultNodeProvisionerRouter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class AppNodeProvisionerRouter extends DefaultNodeProvisionerRouter {

    protected AppNodeProvisionerRouter() {
        super(java.util.List.of());
    }

    @Inject
    public AppNodeProvisionerRouter(Instance<NodeProvisioner> provisioners) {
        super(java.util.stream.StreamSupport.stream(provisioners.spliterator(), false).toList());
    }
}
