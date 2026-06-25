package io.casehub.ops.iot;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class IoTNodeProvisioner implements NodeProvisioner {

    private static final String DISPATCHED_BY = "casehub-ops-iot-provisioner";

    private final DeviceRegistry registry;
    private final Map<String, DeviceProvider> providers;

    @Inject
    public IoTNodeProvisioner(DeviceRegistry registry,
                               @Any Instance<DeviceProvider> providerBeans) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerBeans.forEach(p -> providers.put(p.providerId(), p));
    }

    IoTNodeProvisioner(DeviceRegistry registry, List<DeviceProvider> providerList) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerList.forEach(p -> providers.put(p.providerId(), p));
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        return switch (node.spec()) {
            case PhysicalDeviceSpec s ->
                new ProvisionResult.Failed("physical devices cannot be auto-provisioned");
            case DeviceConfigSpec s -> provisionConfig(s);
            default -> new ProvisionResult.Failed("unknown spec type: " + node.spec().getClass());
        };
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        return switch (node.spec()) {
            case PhysicalDeviceSpec s ->
                new DeprovisionResult.Failed("physical devices cannot be auto-deprovisioned");
            case DeviceConfigSpec s -> new DeprovisionResult.Success();
            default -> new DeprovisionResult.Failed("unknown spec type");
        };
    }

    private ProvisionResult provisionConfig(DeviceConfigSpec spec) {
        var optDevice = registry.findById(spec.deviceId());
        if (optDevice.isEmpty()) {
            return new ProvisionResult.Failed("device not present: " + spec.deviceId());
        }
        var device = optDevice.get();

        var provider = providers.get(device.providerId());
        if (provider == null) {
            return new ProvisionResult.Failed(
                "no provider for '" + device.providerId() + "'");
        }

        var actualNorm = CapabilityNormalizer.normalize(device.capabilities());
        var desiredNorm = CapabilityNormalizer.normalize(spec.desiredCapabilities());
        var ctx = new CapabilityCommandMapper.CommandContext(
            DISPATCHED_BY, UUID.randomUUID().toString());

        for (var entry : desiredNorm.entrySet()) {
            Object actualVal = actualNorm.get(entry.getKey());
            if (!entry.getValue().equals(actualVal)) {
                DeviceCommand cmd = CapabilityCommandMapper.toCommand(
                    spec.deviceId(), entry.getKey(),
                    spec.desiredCapabilities().get(entry.getKey()), ctx);
                CommandResult result = provider.dispatch(cmd).await().indefinitely();
                if (result != CommandResult.SENT) {
                    return new ProvisionResult.Failed(
                        "command " + cmd.action() + " returned " + result);
                }
            }
        }
        return new ProvisionResult.Success();
    }
}
