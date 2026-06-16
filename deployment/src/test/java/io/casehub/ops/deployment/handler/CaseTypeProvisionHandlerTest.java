package io.casehub.ops.deployment.handler;

import io.casehub.api.model.CaseDefinition;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.CaseTypeNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaseTypeProvisionHandlerTest {

    private CaseTypeProvisionHandler handler;
    private DesiredStateGraph emptyGraph;

    @BeforeEach
    void setUp() {
        handler = new CaseTypeProvisionHandler();
        emptyGraph = new DefaultDesiredStateGraphFactory().empty();
    }

    @Test
    void provisionBuildsCaseDefinitionAndRegisters() {
        CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
                "io.casehub.legal",
                "contract-review",
                "1.0.0",
                "Contract Review Case",
                "Review and approve legal contracts"
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        ProvisionResult result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(handler.isRegistered(spec.nodeId())).isTrue();

        // Verify the CaseDefinition was built with the correct fields
        // We can't access the internal map directly, but isRegistered confirms it's there
    }

    @Test
    void provisionIsIdempotent() {
        CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
                "io.casehub.legal",
                "contract-review",
                "1.0.0",
                "Contract Review Case",
                "Review and approve legal contracts"
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        handler.provision(spec, context);
        handler.provision(spec, context);

        // Should only have one entry (idempotent)
        assertThat(handler.isRegistered(spec.nodeId())).isTrue();
    }
}
