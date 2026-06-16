package io.casehub.ops.deployment.handler;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProvisionHandlerTest {

    private StubAgentRegistry agentRegistry;
    private AgentProvisionHandler handler;
    private DesiredStateGraph emptyGraph;

    @BeforeEach
    void setUp() {
        agentRegistry = new StubAgentRegistry();
        handler = new AgentProvisionHandler(agentRegistry);
        emptyGraph = new DefaultDesiredStateGraphFactory().empty();
    }

    @Test
    void provisionRegistersAgentDescriptor() {
        AgentCapability analyzeCapability = new AgentCapability(
                "analyze", 0.9, 500L, "low", List.of("text"), List.of("analysis"), List.of(), Map.of()
        );
        AgentCapability classifyCapability = new AgentCapability(
                "classify", 0.85, 300L, "low", List.of("text"), List.of("category"), List.of(), Map.of()
        );
        AgentDisposition disposition = AgentDisposition.builder()
                .socialOrient("collaborative")
                .ruleFollowing("strict")
                .delegation(false)
                .build();

        AgentNodeSpec spec = new AgentNodeSpec(
                "agent-1",
                "Test Agent",
                "triage",
                "anthropic",
                "claude",
                "4.6",
                "1.0.0",
                "fingerprint-123",
                "http://vocab.example.org/case-management",
                "http://vocab.example.org/triage",
                "http://vocab.example.org/disposition",
                Map.of(DispositionAxis.AUTONOMY, "http://vocab.example.org/autonomy"),
                List.of(analyzeCapability, classifyCapability),
                disposition,
                "EU",
                "GDPR-compliant"
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);
        ProvisionResult result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        Optional<AgentDescriptor> registered = agentRegistry.findById("agent-1", "tenant-1");
        assertThat(registered).isPresent();

        AgentDescriptor descriptor = registered.get();
        assertThat(descriptor.agentId()).isEqualTo("agent-1");
        assertThat(descriptor.name()).isEqualTo("Test Agent");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.provider()).isEqualTo("anthropic");
        assertThat(descriptor.modelFamily()).isEqualTo("claude");
        assertThat(descriptor.modelVersion()).isEqualTo("4.6");
        assertThat(descriptor.weightsFingerprint()).isEqualTo("fingerprint-123");
        assertThat(descriptor.domainVocabulary()).isEqualTo("http://vocab.example.org/case-management");
        assertThat(descriptor.slotVocabulary()).isEqualTo("http://vocab.example.org/triage");
        assertThat(descriptor.dispositionVocabulary()).isEqualTo("http://vocab.example.org/disposition");
        assertThat(descriptor.axisVocabularies())
                .containsEntry(DispositionAxis.AUTONOMY, "http://vocab.example.org/autonomy");
        assertThat(descriptor.slot()).isEqualTo("triage");
        assertThat(descriptor.capabilities()).containsExactly(analyzeCapability, classifyCapability);
        assertThat(descriptor.disposition()).isEqualTo(disposition);
        assertThat(descriptor.jurisdiction()).isEqualTo("EU");
        assertThat(descriptor.dataHandlingPolicy()).isEqualTo("GDPR-compliant");
        assertThat(descriptor.tenancyId()).isEqualTo("tenant-1");
    }

    @Test
    void provisionIsIdempotent_upsertReplacesDescriptor() {
        AgentCapability cap1 = new AgentCapability(
                "cap-1", null, null, null, List.of(), List.of(), List.of(), Map.of()
        );
        AgentCapability cap2 = new AgentCapability(
                "cap-2", null, null, null, List.of(), List.of(), List.of(), Map.of()
        );
        AgentDisposition disp1 = AgentDisposition.builder().delegation(false).build();
        AgentDisposition disp2 = AgentDisposition.builder().delegation(true).build();

        AgentNodeSpec spec1 = new AgentNodeSpec(
                "agent-1",
                "First Version",
                "slot-1",
                "anthropic",
                "claude",
                "4.6",
                "1.0.0",
                "fingerprint-123",
                "domain-1",
                "slot-1",
                "disp-1",
                Map.of(),
                List.of(cap1),
                disp1,
                "US",
                "policy-1"
        );

        AgentNodeSpec spec2 = new AgentNodeSpec(
                "agent-1",
                "Second Version",
                "slot-2",
                "anthropic",
                "claude",
                "5.0",
                "2.0.0",
                "fingerprint-456",
                "domain-2",
                "slot-2",
                "disp-2",
                Map.of(),
                List.of(cap2),
                disp2,
                "EU",
                "policy-2"
        );

        ProvisionContext context = new ProvisionContext("tenant-1", emptyGraph);

        handler.provision(spec1, context);
        handler.provision(spec2, context);

        Optional<AgentDescriptor> registered = agentRegistry.findById("agent-1", "tenant-1");
        assertThat(registered).isPresent();

        AgentDescriptor descriptor = registered.get();
        assertThat(descriptor.name()).isEqualTo("Second Version");
        assertThat(descriptor.version()).isEqualTo("2.0.0");
        assertThat(descriptor.modelVersion()).isEqualTo("5.0");
    }

    @Test
    void deprovisionReturnsSuccess() {
        AgentCapability cap = new AgentCapability(
                "cap", null, null, null, List.of(), List.of(), List.of(), Map.of()
        );
        AgentDisposition disp = AgentDisposition.builder().delegation(false).build();

        AgentNodeSpec spec = new AgentNodeSpec(
                "agent-1",
                "Test Agent",
                "slot",
                "anthropic",
                "claude",
                "4.6",
                "1.0.0",
                "fingerprint-123",
                "domain",
                "slot",
                "disp",
                Map.of(),
                List.of(cap),
                disp,
                "US",
                "policy"
        );

        DeprovisionContext context = new DeprovisionContext("tenant-1", emptyGraph);
        DeprovisionResult result = handler.deprovision(spec, context);

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    static class StubAgentRegistry implements AgentRegistry {
        private final ConcurrentHashMap<String, AgentDescriptor> agents = new ConcurrentHashMap<>();

        @Override
        public void register(AgentDescriptor descriptor) {
            String key = descriptor.agentId() + ":" + descriptor.tenancyId();
            agents.put(key, descriptor);
        }

        @Override
        public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
            String key = agentId + ":" + tenancyId;
            return Optional.ofNullable(agents.get(key));
        }

        @Override
        public List<AgentDescriptor> find(AgentQuery query) {
            return List.copyOf(agents.values());
        }
    }
}
