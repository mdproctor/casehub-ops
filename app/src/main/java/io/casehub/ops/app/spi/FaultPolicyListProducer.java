package io.casehub.ops.app.spi;

import io.casehub.desiredstate.api.FaultPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Produces a {@code List<FaultPolicy>} from all CDI-discovered FaultPolicy beans.
 * Required because FaultPolicyEngine injects {@code List<FaultPolicy>} without
 * {@code @All}, and Quarkus CDI does not auto-collect beans into plain List injection.
 */
@ApplicationScoped
public class FaultPolicyListProducer {

    @Inject
    Instance<FaultPolicy> policies;

    @Produces
    @ApplicationScoped
    public List<FaultPolicy> produceFaultPolicies() {
        return StreamSupport.stream(policies.spliterator(), false).toList();
    }
}
