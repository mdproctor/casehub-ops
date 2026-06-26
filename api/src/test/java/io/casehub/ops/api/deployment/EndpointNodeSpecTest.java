package io.casehub.ops.api.deployment;

import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class EndpointNodeSpecTest {

    @Test
    void validConstruction() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.path()).isEqualTo("streams/vitals");
        assertThat(spec.type()).isEqualTo(EndpointType.SERVICE);
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.KAFKA);
        assertThat(spec.properties()).containsEntry(EndpointPropertyKeys.TOPIC, "patient.vitals");
        assertThat(spec.capabilities()).containsExactly(EndpointCapability.RECEIVE);
        assertThat(spec.credentialRef()).isNull();
    }

    @Test
    void nodeIdReturnsPath() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.nodeId()).isEqualTo("streams/vitals");
    }

    @Test
    void nodeTypeReturnsEndpoint() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.nodeType()).isEqualTo("endpoint");
    }

    @Test
    void blankPathRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "  ", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path is required");
    }

    @Test
    void nullPathRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                null, EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path is required");
    }

    @Test
    void nullTypeRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "test/path", null, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullProtocolRejected() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "test/path", EndpointType.SERVICE, null,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), null, Set.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void kafkaRequiresTopic() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(), null, Set.of(EndpointCapability.RECEIVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KAFKA")
                .hasMessageContaining(EndpointPropertyKeys.TOPIC);
    }

    @Test
    void httpRequiresUrl() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/GRPC")
                .hasMessageContaining(EndpointPropertyKeys.URL);
    }

    @Test
    void grpcRequiresUrl() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "services/inference", EndpointType.SERVICE, EndpointProtocol.GRPC,
                Map.of(), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/GRPC");
    }

    @Test
    void kafkaRejectsBlankTopic() {
        assertThatThrownBy(() -> new EndpointNodeSpec(
                "streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "  "), null, Set.of(EndpointCapability.RECEIVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KAFKA");
    }

    @Test
    void amqpPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "queues/events", EndpointType.SERVICE, EndpointProtocol.AMQP,
                Map.of(), null, Set.of(EndpointCapability.RECEIVE));
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.AMQP);
    }

    @Test
    void mcpPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "tools/github", EndpointType.SERVICE, EndpointProtocol.MCP,
                Map.of("serverName", "github"), null, Set.of(EndpointCapability.QUERY));
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.MCP);
    }

    @Test
    void camelPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "routes/ftp-ingest", EndpointType.SERVICE, EndpointProtocol.CAMEL,
                Map.of(), null, Set.of());
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.CAMEL);
    }

    @Test
    void qhorusPassesWithoutRequiredProperties() {
        var spec = new EndpointNodeSpec(
                "internal/mesh", EndpointType.SERVICE, EndpointProtocol.QHORUS,
                Map.of(), null, Set.of());
        assertThat(spec.protocol()).isEqualTo(EndpointProtocol.QHORUS);
    }

    @Test
    void propertiesAreImmutable() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThatThrownBy(() -> spec.properties().put("new", "entry"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void capabilitiesAreImmutable() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThatThrownBy(() -> spec.capabilities().add(EndpointCapability.SEND))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullPropertiesDefaultsToEmpty() {
        var spec = new EndpointNodeSpec(
                "tools/github", EndpointType.SERVICE, EndpointProtocol.MCP,
                null, null, Set.of());
        assertThat(spec.properties()).isEmpty();
    }

    @Test
    void nullCapabilitiesDefaultsToEmpty() {
        var spec = new EndpointNodeSpec(
                "tools/github", EndpointType.SERVICE, EndpointProtocol.MCP,
                Map.of(), null, null);
        assertThat(spec.capabilities()).isEmpty();
    }

    @Test
    void credentialRefIsNullable() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        assertThat(spec.credentialRef()).isNull();

        var withCreds = new EndpointNodeSpec(
                "services/api", EndpointType.SERVICE, EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, "http://localhost"), "api-creds",
                Set.of(EndpointCapability.QUERY));
        assertThat(withCreds.credentialRef()).isEqualTo("api-creds");
    }

    @Test
    void toDescriptorMapsAllFields() {
        var spec = new EndpointNodeSpec(
                "streams/vitals", EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "patient.vitals"),
                "kafka-creds", Set.of(EndpointCapability.RECEIVE));

        var descriptor = spec.toDescriptor("tenant-1");

        assertThat(descriptor.path()).isEqualTo(Path.parse("streams/vitals"));
        assertThat(descriptor.tenancyId()).isEqualTo("tenant-1");
        assertThat(descriptor.type()).isEqualTo(EndpointType.SERVICE);
        assertThat(descriptor.protocol()).isEqualTo(EndpointProtocol.KAFKA);
        assertThat(descriptor.properties()).containsEntry(EndpointPropertyKeys.TOPIC, "patient.vitals");
        assertThat(descriptor.credentialRef()).isEqualTo("kafka-creds");
        assertThat(descriptor.capabilities()).containsExactly(EndpointCapability.RECEIVE);
    }

    @Test
    void toDescriptorTenancyIdInjected() {
        var spec = kafkaEndpoint("streams/vitals", "patient.vitals");
        var d1 = spec.toDescriptor("tenant-a");
        var d2 = spec.toDescriptor("tenant-b");
        assertThat(d1.tenancyId()).isEqualTo("tenant-a");
        assertThat(d2.tenancyId()).isEqualTo("tenant-b");
    }

    private EndpointNodeSpec kafkaEndpoint(String path, String topic) {
        return new EndpointNodeSpec(path, EndpointType.SERVICE, EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, topic), null,
                Set.of(EndpointCapability.RECEIVE));
    }
}
