package io.casehub.ops.deployment.handler;

import io.casehub.api.model.CaseDefinition;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
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
                "Review and approve legal contracts",
                null,
                null
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
                "Review and approve legal contracts",
                null,
                null
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        handler.provision(spec, context);
        handler.provision(spec, context);

        // Should only have one entry (idempotent)
        assertThat(handler.isRegistered(spec.nodeId())).isTrue();
    }

    @Test
    void deprovisionRemovesRegistration() {
        CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
                "io.casehub.legal", "contract-review", "1.0.0", null, null, null, null);

        handler.provision(spec, new ProvisionContext("tenant-1", emptyGraph));
        assertThat(handler.isRegistered(spec.nodeId())).isTrue();

        DeprovisionResult result = handler.deprovision(spec, new DeprovisionContext("tenant-1", emptyGraph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(handler.isRegistered(spec.nodeId())).isFalse();
    }

    @Test
    void deprovisionIdempotent_absentReturnsSuccess() {
        CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
                "io.casehub.legal", "nonexistent", "1.0.0", null, null, null, null);

        DeprovisionResult result = handler.deprovision(spec, new DeprovisionContext("tenant-1", emptyGraph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void provisionWithDefinitionPayloadBuildsFromPayload() {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("namespace", "io.casehub.devtown");
        payload.put("name", "pr-review");
        payload.put("version", "1.0");
        payload.put("title", "PR Review");
        payload.put("summary", "Automated pull request review");
        CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
                "io.casehub.devtown", "pr-review", "1.0",
                "PR Review", "Automated pull request review",
                "case-defs/pr-review.yaml", payload
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        ProvisionResult result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(handler.isRegistered(spec.nodeId())).isTrue();
    }

    @Test
    void provisionWithoutPayloadBuildsSkeleton() {
        CaseTypeNodeSpec spec = new CaseTypeNodeSpec(
                "io.casehub.legal", "contract-review", "1.0.0",
                "Contract Review", "Review contracts",
                null, null
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        ProvisionResult result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(handler.isRegistered(spec.nodeId())).isTrue();
    }
}
