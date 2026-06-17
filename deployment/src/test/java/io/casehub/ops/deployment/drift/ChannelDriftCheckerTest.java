package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
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
    private Map<String, Channel> channels;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        channels = new ConcurrentHashMap<>();
        checker = new ChannelDriftChecker(name -> Optional.ofNullable(channels.get(name)));
    }

    @Test
    void nodeType() {
        assertEquals("channel", checker.nodeType());
    }

    @Test
    void channelPresent() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = null;
        ch.rateLimitPerInstance = null;
        channels.put("dev/work", ch);

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
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = null;
        ch.rateLimitPerInstance = null;
        channels.put("dev/work", ch);

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
        ch.allowedTypes = null;
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = 100;
        ch.rateLimitPerInstance = null;
        channels.put("dev/work", ch);

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
}
