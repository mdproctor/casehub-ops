package io.casehub.ops.deployment.handler;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.EndpointNodeSpec;
import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

public class EndpointProvisionHandlerTest {

    private EndpointProvisionHandler handler;
    private StubEndpointRegistry registry;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        registry = new StubEndpointRegistry();
        handler = new EndpointProvisionHandler(registry);
    }

    @Test
    void provisionRegistersEndpoint() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var context = new ProvisionContext(TENANCY_ID, new DefaultDesiredStateGraphFactory().empty());

        var result = handler.provision(spec, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        var registered = registry.resolve(Path.parse("streams/vitals"), TENANCY_ID);
        assertThat(registered).isPresent();
        assertThat(registered.get().protocol()).isEqualTo(EndpointProtocol.KAFKA);
        assertThat(registered.get().properties()).containsEntry(EndpointPropertyKeys.TOPIC, "patient.vitals");
        assertThat(registered.get().tenancyId()).isEqualTo(TENANCY_ID);
    }

    @Test
    void provisionMapsAllFields() {
        var spec = new EndpointNodeSpec(
                "services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://api:8080"),
                "api-creds", Set.of(EndpointCapability.QUERY, EndpointCapability.DISPATCH));
        var context = new ProvisionContext(TENANCY_ID, new DefaultDesiredStateGraphFactory().empty());

        handler.provision(spec, context);

        var registered = registry.resolve(Path.parse("services/api"), TENANCY_ID).orElseThrow();
        assertThat(registered.type()).isEqualTo(EndpointType.SERVICE);
        assertThat(registered.protocol()).isEqualTo(EndpointProtocol.HTTP);
        assertThat(registered.credentialRef()).isEqualTo("api-creds");
        assertThat(registered.capabilities()).containsExactlyInAnyOrder(
                EndpointCapability.QUERY, EndpointCapability.DISPATCH);
    }

    @Test
    void provisionWithNullCredentialRef() {
        var spec = kafkaSpec("streams/events", "events");
        var context = new ProvisionContext(TENANCY_ID, new DefaultDesiredStateGraphFactory().empty());

        handler.provision(spec, context);

        var registered = registry.resolve(Path.parse("streams/events"), TENANCY_ID).orElseThrow();
        assertThat(registered.credentialRef()).isNull();
    }

    @Test
    void deprovisionDeregistersEndpoint() {
        var spec = kafkaSpec("streams/vitals", "patient.vitals");
        var graph = new DefaultDesiredStateGraphFactory().empty();
        handler.provision(spec, new ProvisionContext(TENANCY_ID, graph));
        assertThat(registry.resolve(Path.parse("streams/vitals"), TENANCY_ID)).isPresent();

        var result = handler.deprovision(spec, new DeprovisionContext(TENANCY_ID, graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(registry.resolve(Path.parse("streams/vitals"), TENANCY_ID)).isEmpty();
    }

    private EndpointNodeSpec kafkaSpec(String path, String topic) {
        return new EndpointNodeSpec(path, EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, topic), null,
                Set.of(EndpointCapability.RECEIVE));
    }

    public static class StubEndpointRegistry implements EndpointRegistry {
        private final Map<String, EndpointDescriptor> endpoints = new ConcurrentHashMap<>();

        private String key(Path path, String tenancyId) {
            return path.value() + ":" + tenancyId;
        }

        @Override
        public void register(EndpointDescriptor endpoint) {
            endpoints.put(key(endpoint.path(), endpoint.tenancyId()), endpoint);
        }

        @Override
        public Optional<EndpointDescriptor> resolve(Path path, String tenancyId) {
            return Optional.ofNullable(endpoints.get(key(path, tenancyId)));
        }

        @Override
        public List<EndpointDescriptor> discover(EndpointQuery query) {
            return new ArrayList<>(endpoints.values());
        }

        @Override
        public void deregister(Path path, String tenancyId) {
            endpoints.remove(key(path, tenancyId));
        }
    }
}
