package io.casehub.ops.deployment;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Deployment-module implementation of TrustRoutingPolicyProvider.
 * Maintains an in-memory map of per-capability policies, populated by TrustPolicyProvisionHandler.
 * Returns TrustRoutingPolicy.DEFAULT for capabilities without an explicit policy.
 */
@ApplicationScoped
public class DeploymentTrustRoutingPolicyProvider implements TrustRoutingPolicyProvider {

    private final ConcurrentHashMap<String, TrustRoutingPolicy> policies = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "ops-deployment";
    }

    @Override
    public TrustRoutingPolicy forCapability(String capabilityName) {
        return policies.getOrDefault(capabilityName, TrustRoutingPolicy.DEFAULT);
    }

    /**
     * Store a policy for the given capability. Called by TrustPolicyProvisionHandler during provision.
     */
    public void store(String capability, TrustRoutingPolicy policy) {
        policies.put(capability, policy);
    }

    /**
     * Remove a policy for the given capability. Called by TrustPolicyProvisionHandler during deprovision.
     */
    public void remove(String capability) {
        policies.remove(capability);
    }
}
