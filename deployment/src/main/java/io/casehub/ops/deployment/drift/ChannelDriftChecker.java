package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelConnectorBinding;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.casehub.qhorus.api.store.CrossTenantChannelStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChannelDriftChecker implements NodeDriftChecker {

    private static final Logger LOG = Logger.getLogger(ChannelDriftChecker.class);

    private final CrossTenantChannelStore channelStore;
    private final ChannelBindingStore bindingStore;

    @Inject
    public ChannelDriftChecker(CrossTenantChannelStore channelStore, ChannelBindingStore bindingStore) {
        this.channelStore = channelStore;
        this.bindingStore = bindingStore;
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

        Optional<Channel> actual = channelStore.findByNameAndTenancy(channelSpec.name(), tenancyId);
        if (actual.isEmpty()) {
            return NodeStatus.ABSENT;
        }

        boolean drifted = false;
        Channel ch = actual.get();

        // Channel field comparison
        if (!fieldMatch("description", channelSpec.name(), channelSpec.description(), ch.description())) {
            drifted = true;
        }
        if (!fieldMatch("allowedTypes", channelSpec.name(), channelSpec.allowedTypes(), ch.allowedTypes())) {
            drifted = true;
        }
        if (!fieldMatch("deniedTypes", channelSpec.name(), channelSpec.deniedTypes(), ch.deniedTypes())) {
            drifted = true;
        }
        if (!fieldMatch("rateLimitPerChannel", channelSpec.name(), channelSpec.rateLimitPerChannel(), ch.rateLimitPerChannel())) {
            drifted = true;
        }
        if (!fieldMatch("rateLimitPerInstance", channelSpec.name(), channelSpec.rateLimitPerInstance(), ch.rateLimitPerInstance())) {
            drifted = true;
        }
        if (!csvListMatch("allowedWriters", channelSpec.name(), channelSpec.allowedWriters(), ch.allowedWriters())) {
            drifted = true;
        }
        if (!csvListMatch("adminInstances", channelSpec.name(), channelSpec.adminInstances(), ch.adminInstances())) {
            drifted = true;
        }
        if (!csvListMatch("barrierContributors", channelSpec.name(), channelSpec.barrierContributors(), ch.barrierContributors())) {
            drifted = true;
        }

        // Binding comparison — always check, even if fields already drifted
        if (!bindingMatch(channelSpec, ch.id())) {
            drifted = true;
        }

        return drifted ? NodeStatus.DRIFTED : NodeStatus.PRESENT;
    }

    private boolean fieldMatch(String fieldName, String channelName, Object desired, Object actual) {
        if (Objects.equals(desired, actual)) {
            return true;
        }
        LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desired, actual);
        return false;
    }

    /**
     * Compares a CSV string (from the spec) against a List (from the Channel record),
     * using order-independent set comparison.
     */
    private boolean csvListMatch(String fieldName, String channelName, String desiredCsv, java.util.List<String> actual) {
        Set<String> desiredSet = parseCsvSet(desiredCsv);
        Set<String> actualSet = actual != null ? new TreeSet<>(actual) : Set.of();
        if (desiredSet.equals(actualSet)) {
            return true;
        }
        LOG.debugf("channel %s: %s drifted [%s → %s]", channelName, fieldName, desiredSet, actualSet);
        return false;
    }

    private Set<String> parseCsvSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private boolean bindingMatch(ChannelNodeSpec spec, java.util.UUID channelId) {
        boolean specHasBinding = spec.inboundConnectorId() != null
                || spec.externalKey() != null
                || spec.outboundConnectorId() != null
                || spec.outboundDestination() != null;

        Optional<ChannelConnectorBinding> actualBinding = bindingStore.findByChannelId(channelId);

        if (!specHasBinding && actualBinding.isEmpty()) {
            return true;
        }
        if (specHasBinding && actualBinding.isEmpty()) {
            LOG.debugf("channel %s: binding expected but absent", spec.name());
            return false;
        }
        if (!specHasBinding && actualBinding.isPresent()) {
            LOG.debugf("channel %s: binding present but not in spec (reverse asymmetry)", spec.name());
            return false;
        }

        ChannelConnectorBinding binding = actualBinding.get();
        boolean match = true;
        if (!Objects.equals(spec.inboundConnectorId(), binding.inboundConnectorId())) {
            LOG.debugf("channel %s: binding.inboundConnectorId drifted [%s → %s]",
                    spec.name(), spec.inboundConnectorId(), binding.inboundConnectorId());
            match = false;
        }
        if (!Objects.equals(spec.externalKey(), binding.externalKey())) {
            LOG.debugf("channel %s: binding.externalKey drifted [%s → %s]",
                    spec.name(), spec.externalKey(), binding.externalKey());
            match = false;
        }
        if (!Objects.equals(spec.outboundConnectorId(), binding.outboundConnectorId())) {
            LOG.debugf("channel %s: binding.outboundConnectorId drifted [%s → %s]",
                    spec.name(), spec.outboundConnectorId(), binding.outboundConnectorId());
            match = false;
        }
        if (!Objects.equals(spec.outboundDestination(), binding.outboundDestination())) {
            LOG.debugf("channel %s: binding.outboundDestination drifted [%s → %s]",
                    spec.name(), spec.outboundDestination(), binding.outboundDestination());
            match = false;
        }
        return match;
    }
}
