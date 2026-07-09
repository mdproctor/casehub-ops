package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelDriftCheckerTest {

    private ChannelDriftChecker checker;
    private CrossTenantChannelStore channelStore;
    private ChannelBindingStore bindingStore;
    private Map<String, Channel> channels; // keyed by "name:tenancyId"
    private Map<UUID, ChannelConnectorBinding> bindings;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        channels = new ConcurrentHashMap<>();
        bindings = new ConcurrentHashMap<>();

        channelStore = new CrossTenantChannelStore() {
            @Override
            public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
                return Optional.ofNullable(channels.get(name + ":" + tenancyId));
            }
            @Override
            public List<Channel> listAll() { throw new UnsupportedOperationException(); }
            @Override
            public Optional<Channel> findById(UUID id) { throw new UnsupportedOperationException(); }
        };

        bindingStore = new ChannelBindingStore() {
            @Override
            public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
                return Optional.ofNullable(bindings.get(channelId));
            }
            @Override
            public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) { throw new UnsupportedOperationException(); }
            @Override
            public void put(ChannelConnectorBinding binding) { throw new UnsupportedOperationException(); }
            @Override
            public void delete(UUID channelId) { throw new UnsupportedOperationException(); }
            @Override
            public Map<UUID, ChannelConnectorBinding> findAll() { throw new UnsupportedOperationException(); }
        };

        checker = new ChannelDriftChecker(channelStore, bindingStore);
    }

    @Test
    void nodeType() {
        assertEquals("channel", checker.nodeType());
    }

    @Test
    void channelPresent_tenancyUsedInLookup() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:tenant-1", ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        // Found under tenant-1
        assertEquals(NodeStatus.PRESENT, checker.check(spec, "tenant-1"));
        // Not found under tenant-2
        assertEquals(NodeStatus.ABSENT, checker.check(spec, "tenant-2"));
    }

    @Test
    void channelPresent() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .description("desc")
                .allowedTypes(Set.of(MessageType.COMMAND))
                .deniedTypes(Set.of())
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelAbsent() {
        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.ABSENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_allowedTypesMismatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .description("desc")
                .allowedTypes(Set.of(MessageType.COMMAND))
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.EVENT), Set.of(), null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_rateLimitMismatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .description("desc")
                .rateLimitPerChannel(100)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                null, null, null, null, null, 200, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void unknownSpecType() {
        var spec = new io.casehub.ops.api.deployment.AgentNodeSpec(
                "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(), null, "US", "policy", null, List.of());

        assertEquals(NodeStatus.UNKNOWN, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelPresent_allFieldsMatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .description("Work channel")
                .semantic(ChannelSemantic.APPEND)
                .allowedTypes(Set.of(MessageType.COMMAND))
                .allowedWriters(List.of("agent-1", "agent-2"))
                .adminInstances(List.of("admin-1"))
                .barrierContributors(List.of("contrib-1", "contrib-2"))
                .rateLimitPerChannel(100)
                .rateLimitPerInstance(10)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "Work channel", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), null, "agent-1,agent-2", "admin-1", "contrib-1,contrib-2",
                100, 10, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_descriptionMismatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .description("Old description")
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "New description", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_allowedWritersOrderInsensitive() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .allowedWriters(List.of("bob", "alice"))
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, "alice,bob", null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_allowedWritersMismatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .allowedWriters(List.of("alice", "charlie"))
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, "alice,bob", null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_adminInstancesMismatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .adminInstances(List.of("admin-old"))
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, "admin-new", null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_barrierContributorsMismatch() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .barrierContributors(List.of("contrib-old"))
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, "contrib-new", null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelPresent_withMatchingBinding() {
        UUID channelId = UUID.randomUUID();
        Channel ch = Channel.builder("dev/work")
                .id(channelId)
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        ChannelConnectorBinding binding = new ChannelConnectorBinding(
                channelId, "slack", "C12345", "slack", "#general");
        bindings.put(channelId, binding);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, "slack", "C12345", "slack", "#general");

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_bindingFieldMismatch() {
        UUID channelId = UUID.randomUUID();
        Channel ch = Channel.builder("dev/work")
                .id(channelId)
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        ChannelConnectorBinding binding = new ChannelConnectorBinding(
                channelId, "slack", "C12345", "slack", "#old-channel");
        bindings.put(channelId, binding);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, "slack", "C12345", "slack", "#new-channel");

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_bindingExpectedButAbsent() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);
        // No binding in bindings map

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, "slack", "C12345", "slack", "#general");

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_bindingPresentButNotInSpec() {
        UUID channelId = UUID.randomUUID();
        Channel ch = Channel.builder("dev/work")
                .id(channelId)
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);

        ChannelConnectorBinding binding = new ChannelConnectorBinding(
                channelId, "slack", "C12345", "slack", "#general");
        bindings.put(channelId, binding);

        // Spec has no binding fields (all null)
        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelPresent_noBindingEitherSide() {
        Channel ch = Channel.builder("dev/work")
                .id(UUID.randomUUID())
                .semantic(ChannelSemantic.APPEND)
                .build();
        channels.put("dev/work:" + TENANCY_ID, ch);
        // No binding in bindings map, no binding in spec

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }
}
