package io.casehub.ops.deployment.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelProvisionHandlerTest {

    private StubChannelOps stub;
    private ChannelProvisionHandler handler;
    private DesiredStateGraph emptyGraph;

    @BeforeEach
    void setUp() {
        stub = new StubChannelOps();
        handler = new ChannelProvisionHandler(stub);
        emptyGraph = new DefaultDesiredStateGraphFactory().empty();
    }

    @Test
    void provisionCreatesChannelWhenAbsent() {
        var spec = new ChannelNodeSpec(
                "alerts",
                "Alert notifications",
                ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND, MessageType.EVENT),
                Set.of(MessageType.STATUS),
                "agent:monitor",
                "agent:admin",
                null, // barrierContributors
                100, // rateLimitPerChannel
                10, // rateLimitPerInstance
                null, // inboundConnectorId
                null, // externalKey
                null, // outboundConnectorId
                null // outboundDestination
        );

        ProvisionResult result = handler.provision(spec, new ProvisionContext("test-tenancy", emptyGraph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        Channel created = stub.findByName("alerts").orElseThrow();
        assertThat(created.name()).isEqualTo("alerts");
        assertThat(created.description()).isEqualTo("Alert notifications");
        assertThat(created.semantic()).isEqualTo(ChannelSemantic.APPEND);
        assertThat(created.allowedWriters()).isEqualTo(List.of("agent:monitor"));
        assertThat(created.adminInstances()).isEqualTo(List.of("agent:admin"));
        assertThat(created.rateLimitPerChannel()).isEqualTo(100);
        assertThat(created.rateLimitPerInstance()).isEqualTo(10);
        assertThat(created.allowedTypes()).isEqualTo(Set.of(MessageType.COMMAND, MessageType.EVENT));
        assertThat(created.deniedTypes()).isEqualTo(Set.of(MessageType.STATUS));
    }

    @Test
    void provisionUpdatesWhenExists() {
        // Pre-create channel with initial values
        var initialSpec = new ChannelNodeSpec(
                "events",
                "Event stream",
                ChannelSemantic.COLLECT,
                Set.of(MessageType.EVENT),
                Set.of(),
                "agent:old-writer",
                "agent:old-admin",
                null,
                50,
                5,
                null, null, null, null
        );
        handler.provision(initialSpec, new ProvisionContext("test-tenancy", emptyGraph));
        Channel initial = stub.findByName("events").orElseThrow();
        UUID initialId = initial.id();

        // Update with new mutable values
        var updatedSpec = new ChannelNodeSpec(
                "events",
                "Updated description", // immutable, should NOT change
                ChannelSemantic.APPEND, // immutable, should NOT change
                Set.of(MessageType.QUERY, MessageType.EVENT),
                Set.of(MessageType.STATUS),
                "agent:new-writer",
                "agent:new-admin",
                null,
                200,
                20,
                null, null, null, null
        );

        ProvisionResult result = handler.provision(updatedSpec, new ProvisionContext("test-tenancy", emptyGraph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        Channel updated = stub.findByName("events").orElseThrow();
        assertThat(updated.id()).isEqualTo(initialId); // Same channel
        assertThat(updated.description()).isEqualTo("Event stream"); // Immutable, unchanged
        assertThat(updated.semantic()).isEqualTo(ChannelSemantic.COLLECT); // Immutable, unchanged
        assertThat(updated.allowedWriters()).isEqualTo(List.of("agent:new-writer"));
        assertThat(updated.adminInstances()).isEqualTo(List.of("agent:new-admin"));
        assertThat(updated.rateLimitPerChannel()).isEqualTo(200);
        assertThat(updated.rateLimitPerInstance()).isEqualTo(20);
        assertThat(updated.allowedTypes()).isEqualTo(Set.of(MessageType.EVENT, MessageType.QUERY));
        assertThat(updated.deniedTypes()).isEqualTo(Set.of(MessageType.STATUS));
    }

    @Test
    void deprovisionRemovesChannel() {
        var spec = new ChannelNodeSpec(
                "temp-channel",
                "Temporary",
                ChannelSemantic.EPHEMERAL,
                Set.of(), Set.of(),
                null, null, null, null, null,
                null, null, null, null
        );
        handler.provision(spec, new ProvisionContext("test-tenancy", emptyGraph));
        assertThat(stub.findByName("temp-channel")).isPresent();

        DeprovisionResult result = handler.deprovision(spec, new DeprovisionContext("test-tenancy", emptyGraph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(stub.findByName("temp-channel")).isEmpty();
    }

    @Test
    void deprovisionIdempotent_absentChannel() {
        var spec = new ChannelNodeSpec(
                "non-existent",
                "Does not exist",
                ChannelSemantic.LAST_WRITE,
                Set.of(), Set.of(),
                null, null, null, null, null,
                null, null, null, null
        );

        DeprovisionResult result = handler.deprovision(spec, new DeprovisionContext("test-tenancy", emptyGraph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    static class StubChannelOps implements ChannelProvisionHandler.ChannelOperations {
        private final Map<String, Channel> channels = new ConcurrentHashMap<>();

        @Override
        public Optional<Channel> findByName(String name) {
            return Optional.ofNullable(channels.get(name));
        }

        @Override
        public Channel create(ChannelCreateRequest req) {
            Channel ch = Channel.builder(req.name())
                    .id(UUID.randomUUID())
                    .description(req.description())
                    .semantic(req.semantic())
                    .barrierContributors(req.barrierContributors())
                    .allowedWriters(req.allowedWriters())
                    .adminInstances(req.adminInstances())
                    .rateLimitPerChannel(req.rateLimitPerChannel())
                    .rateLimitPerInstance(req.rateLimitPerInstance())
                    .allowedTypes(req.allowedTypes())
                    .deniedTypes(req.deniedTypes())
                    .build();
            channels.put(ch.name(), ch);
            return ch;
        }

        @Override
        public void delete(UUID channelId, boolean force) {
            channels.entrySet().removeIf(e -> e.getValue().id().equals(channelId));
        }

        @Override
        public Channel setTypeConstraints(UUID channelId, Set<MessageType> allowed, Set<MessageType> denied) {
            Channel ch = findById(channelId);
            Channel updated = ch.toBuilder().allowedTypes(allowed).deniedTypes(denied).build();
            channels.put(updated.name(), updated);
            return updated;
        }

        @Override
        public Channel setRateLimits(UUID channelId, Integer perChannel, Integer perInstance) {
            Channel ch = findById(channelId);
            Channel updated = ch.toBuilder().rateLimitPerChannel(perChannel).rateLimitPerInstance(perInstance).build();
            channels.put(updated.name(), updated);
            return updated;
        }

        @Override
        public Channel setAllowedWriters(UUID channelId, List<String> allowedWriters) {
            Channel ch = findById(channelId);
            Channel updated = ch.toBuilder().allowedWriters(allowedWriters).build();
            channels.put(updated.name(), updated);
            return updated;
        }

        @Override
        public Channel setAdminInstances(UUID channelId, List<String> adminInstances) {
            Channel ch = findById(channelId);
            Channel updated = ch.toBuilder().adminInstances(adminInstances).build();
            channels.put(updated.name(), updated);
            return updated;
        }

        private Channel findById(UUID id) {
            return channels.values().stream()
                    .filter(ch -> ch.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + id));
        }
    }
}
