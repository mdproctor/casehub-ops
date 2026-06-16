package io.casehub.ops.deployment.handler;

import io.casehub.api.model.CaseDefinition;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.deployment.CaseTypeNodeSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Provisions case type nodes by building and registering CaseDefinitions.
 * Uses in-memory storage rather than CaseDefinitionRegistry to avoid engine-common transitive CDI risks.
 * The "always PRESENT" simplification means case type actual state reads from this internal map.
 */
@ApplicationScoped
public class CaseTypeProvisionHandler {

    private final ConcurrentHashMap<String, CaseDefinition> definitions;

    /**
     * CDI constructor.
     */
    public CaseTypeProvisionHandler() {
        this.definitions = new ConcurrentHashMap<>();
    }

    /**
     * Test constructor accepting explicit map for stubbing.
     */
    CaseTypeProvisionHandler(ConcurrentHashMap<String, CaseDefinition> definitions) {
        this.definitions = definitions;
    }

    public ProvisionResult provision(CaseTypeNodeSpec spec, ProvisionContext context) {
        CaseDefinition definition = CaseDefinition.builder()
                .namespace(spec.namespace())
                .name(spec.name())
                .version(spec.version())
                .title(spec.title())
                .summary(spec.summary())
                .build();

        definitions.put(spec.nodeId(), definition);
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(CaseTypeNodeSpec spec, DeprovisionContext context) {
        definitions.remove(spec.nodeId());
        return new DeprovisionResult.Success();
    }

    /**
     * Check if a case type is registered. Package-visible for the actual state adapter.
     */
    boolean isRegistered(String nodeId) {
        return definitions.containsKey(nodeId);
    }
}
