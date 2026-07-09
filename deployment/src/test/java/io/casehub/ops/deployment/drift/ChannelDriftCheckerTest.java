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
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
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
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.description = "desc";
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = null;
        ch.rateLimitPerInstance = null;
        ch.allowedWriters = null;
        ch.adminInstances = null;
        ch.barrierContributors = null;
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
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.description = "desc";
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = null;
        ch.rateLimitPerInstance = null;
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.EVENT), Set.of(), null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_rateLimitMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.description = "desc";
        ch.allowedTypes = null;
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = 100;
        ch.rateLimitPerInstance = null;
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
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.description = "Work channel";
        ch.semantic = ChannelSemantic.APPEND;
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.allowedWriters = "agent-1,agent-2";
        ch.adminInstances = "admin-1";
        ch.barrierContributors = "contrib-1,contrib-2";
        ch.rateLimitPerChannel = 100;
        ch.rateLimitPerInstance = 10;
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "Work channel", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), null, "agent-1,agent-2", "admin-1", "contrib-1,contrib-2",
                100, 10, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_descriptionMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.description = "Old description";
        ch.semantic = ChannelSemantic.APPEND;
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", "New description", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_allowedWritersOrderInsensitive() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.allowedWriters = "bob,alice";
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, "alice,bob", null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_allowedWritersMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.allowedWriters = "alice,charlie";
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, "alice,bob", null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_adminInstancesMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.adminInstances = "admin-old";
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, "admin-new", null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_barrierContributorsMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.barrierContributors = "contrib-old";
        channels.put("dev/work:" + TENANCY_ID, ch);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, "contrib-new", null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelPresent_withMatchingBinding() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        channels.put("dev/work:" + TENANCY_ID, ch);

        ChannelConnectorBinding binding = new ChannelConnectorBinding();
        binding.channelId = ch.id;
        binding.inboundConnectorId = "slack";
        binding.externalKey = "C12345";
        binding.outboundConnectorId = "slack";
        binding.outboundDestination = "#general";
        bindings.put(ch.id, binding);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, "slack", "C12345", "slack", "#general");

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_bindingFieldMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        channels.put("dev/work:" + TENANCY_ID, ch);

        ChannelConnectorBinding binding = new ChannelConnectorBinding();
        binding.channelId = ch.id;
        binding.inboundConnectorId = "slack";
        binding.externalKey = "C12345";
        binding.outboundConnectorId = "slack";
        binding.outboundDestination = "#old-channel";
        bindings.put(ch.id, binding);

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, "slack", "C12345", "slack", "#new-channel");

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_bindingExpectedButAbsent() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        channels.put("dev/work:" + TENANCY_ID, ch);
        // No binding in bindings map

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, "slack", "C12345", "slack", "#general");

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelDrifted_bindingPresentButNotInSpec() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        channels.put("dev/work:" + TENANCY_ID, ch);

        ChannelConnectorBinding binding = new ChannelConnectorBinding();
        binding.channelId = ch.id;
        binding.inboundConnectorId = "slack";
        binding.externalKey = "C12345";
        binding.outboundConnectorId = "slack";
        binding.outboundDestination = "#general";
        bindings.put(ch.id, binding);

        // Spec has no binding fields (all null)
        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void channelPresent_noBindingEitherSide() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        channels.put("dev/work:" + TENANCY_ID, ch);
        // No binding in bindings map, no binding in spec

        var spec = new ChannelNodeSpec("dev/work", null, ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }
}
