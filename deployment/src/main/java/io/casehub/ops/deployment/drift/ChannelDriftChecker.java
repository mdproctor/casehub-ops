package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks drift for channel nodes by comparing mutable fields (allowedTypes, deniedTypes, rate limits).
 * Extracted from DeploymentActualStateAdapter.checkChannelStatus() and related helpers.
 */
@ApplicationScoped
public class ChannelDriftChecker implements NodeDriftChecker {

    @FunctionalInterface
    public interface ChannelLookup {
        Optional<Channel> findByName(String name);
    }

    private final ChannelLookup channelLookup;

    public ChannelDriftChecker(ChannelLookup channelLookup) {
        this.channelLookup = channelLookup;
    }

    @Override
    public String nodeType() {
        return "channel";
    }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof ChannelNodeSpec channelSpec)) {
            return NodeStatus.UNKNOWN;
        }

        Optional<Channel> actual = channelLookup.findByName(channelSpec.name());
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }

        return mutableFieldsMatch(channelSpec, actual.get()) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }

    private boolean mutableFieldsMatch(ChannelNodeSpec spec, Channel actual) {
        // Compare mutable fields: allowedTypes, deniedTypes, rateLimitPerChannel, rateLimitPerInstance
        if (!allowedTypesMatch(spec.allowedTypes(), actual.allowedTypes)) {
            return false;
        }
        if (!deniedTypesMatch(spec.deniedTypes(), actual.deniedTypes)) {
            return false;
        }
        if (!Objects.equals(spec.rateLimitPerChannel(), actual.rateLimitPerChannel)) {
            return false;
        }
        return Objects.equals(spec.rateLimitPerInstance(), actual.rateLimitPerInstance);
    }

    private boolean allowedTypesMatch(Set<MessageType> desired, String actualCsv) {
        // Both null means match (both open)
        if (desired == null && actualCsv == null) {
            return true;
        }
        // Empty set is treated as null (both open)
        if ((desired == null || desired.isEmpty()) && actualCsv == null) {
            return true;
        }
        if (desired == null && (actualCsv == null || actualCsv.isEmpty())) {
            return true;
        }
        if (desired == null || actualCsv == null) {
            return false;
        }
        Set<MessageType> actualSet = MessageType.parseTypes(actualCsv);
        return desired.equals(actualSet);
    }

    private boolean deniedTypesMatch(Set<MessageType> desired, String actualCsv) {
        // Both null means match (no restrictions)
        if (desired == null && actualCsv == null) {
            return true;
        }
        // Empty set is treated as null (no restrictions)
        if ((desired == null || desired.isEmpty()) && actualCsv == null) {
            return true;
        }
        if (desired == null && (actualCsv == null || actualCsv.isEmpty())) {
            return true;
        }
        if (desired == null || actualCsv == null) {
            return false;
        }
        Set<MessageType> actualSet = MessageType.parseTypes(actualCsv);
        return desired.equals(actualSet);
    }
}
