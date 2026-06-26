package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.ops.deployment.handler.EndpointProvisionHandlerTest.StubEndpointRegistry;
import io.casehub.platform.api.endpoints.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointDriftCheckerTest {

    private EndpointDriftChecker checker;
    private StubEndpointRegistry registry;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        registry = new StubEndpointRegistry();
        checker = new EndpointDriftChecker(registry);
    }

    @Test
    void nodeType() {
        assertEquals("endpoint", checker.nodeType());
    }

    @Test
    void endpointPresent() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        registry.register(spec.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointAbsent() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");

        assertEquals(NodeStatus.ABSENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_protocolChange() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var drifted = new EndpointNodeSpec("streams/vitals", EndpointType.SERVICE, EndpointProtocol.AMQP,
                Map.of(), null, Set.of(EndpointCapability.RECEIVE));
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_propertiesChange() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var drifted = kafkaSpec("streams/vitals", "different.topic");
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_capabilitiesChange() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var drifted = new EndpointNodeSpec("streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "patient.vitals"), null,
                Set.of(EndpointCapability.SEND));
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void endpointDrifted_credentialRefChange() {
        var spec = new EndpointNodeSpec("services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://api:8080"), "creds-v1", Set.of());
        var drifted = new EndpointNodeSpec("services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://api:8080"), "creds-v2", Set.of());
        registry.register(drifted.toDescriptor(TENANCY_ID));

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void unknownSpecType() {
        var spec = new AgentNodeSpec(
                "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", null, null, null, null, null, List.of(), null, null, null, null, List.of());

        assertEquals(NodeStatus.UNKNOWN, checker.check(spec, TENANCY_ID));
    }

    private EndpointNodeSpec kafkaSpec(String path, String topic) {
        return new EndpointNodeSpec(path, EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, topic), null,
                Set.of(EndpointCapability.RECEIVE));
    }
}
