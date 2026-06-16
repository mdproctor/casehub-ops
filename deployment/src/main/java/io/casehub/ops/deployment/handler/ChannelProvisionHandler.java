package io.casehub.ops.deployment.handler;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.deployment.ChannelNodeSpec;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;

@ApplicationScoped
public class ChannelProvisionHandler {

    interface ChannelOperations {
        Optional<Channel> findByName(String name);
        Channel create(ChannelCreateRequest req);
        void delete(UUID channelId, boolean force);
        Channel setTypeConstraints(UUID channelId, Set<MessageType> allowed, Set<MessageType> denied);
        Channel setRateLimits(UUID channelId, Integer perChannel, Integer perInstance);
        Channel setAllowedWriters(UUID channelId, String allowedWriters);
        Channel setAdminInstances(UUID channelId, String adminInstances);
    }

    private final ChannelOperations ops;

    @Inject
    public ChannelProvisionHandler(ChannelService channelService) {
        this.ops = new ChannelOperations() {
            @Override public Optional<Channel> findByName(String name) { return channelService.findByName(name); }
            @Override public Channel create(ChannelCreateRequest req) { return channelService.create(req); }
            @Override public void delete(UUID channelId, boolean force) { channelService.delete(channelId, force); }
            @Override public Channel setTypeConstraints(UUID id, Set<MessageType> a, Set<MessageType> d) { return channelService.setTypeConstraints(id, a, d); }
            @Override public Channel setRateLimits(UUID id, Integer pc, Integer pi) { return channelService.setRateLimits(id, pc, pi); }
            @Override public Channel setAllowedWriters(UUID id, String w) { return channelService.setAllowedWriters(id, w); }
            @Override public Channel setAdminInstances(UUID id, String a) { return channelService.setAdminInstances(id, a); }
        };
    }

    ChannelProvisionHandler(ChannelOperations ops) {
        this.ops = ops;
    }

    public ProvisionResult provision(ChannelNodeSpec spec, ProvisionContext context) {
        Optional<Channel> existing = ops.findByName(spec.name());
        if (existing.isPresent()) {
            updateMutableFields(existing.get().id, spec);
            return new ProvisionResult.Success();
        }

        var request = new ChannelCreateRequest(
                spec.name(), spec.description(), spec.semantic(),
                spec.barrierContributors(), spec.allowedWriters(), spec.adminInstances(),
                spec.rateLimitPerChannel(), spec.rateLimitPerInstance(),
                spec.allowedTypes(), spec.deniedTypes(),
                spec.inboundConnectorId(), spec.externalKey(),
                spec.outboundConnectorId(), spec.outboundDestination());
        ops.create(request);
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(ChannelNodeSpec spec, DeprovisionContext context) {
        Optional<Channel> existing = ops.findByName(spec.name());
        if (existing.isPresent()) {
            ops.delete(existing.get().id, true);
        }
        return new DeprovisionResult.Success();
    }

    private void updateMutableFields(UUID channelId, ChannelNodeSpec spec) {
        ops.setTypeConstraints(channelId, spec.allowedTypes(), spec.deniedTypes());
        ops.setRateLimits(channelId, spec.rateLimitPerChannel(), spec.rateLimitPerInstance());
        ops.setAllowedWriters(channelId, spec.allowedWriters());
        ops.setAdminInstances(channelId, spec.adminInstances());
    }
}
