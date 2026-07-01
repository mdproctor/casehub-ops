package io.casehub.ops.api.iot;

import io.casehub.iot.api.DeviceClass;
import java.util.Objects;

public record PhysicalDeviceSpec(
    String deviceId,
    DeviceClass deviceClass,
    String label
) implements IoTNodeSpec {
    public PhysicalDeviceSpec {
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        Objects.requireNonNull(label, "label required");
    }

    @Override
    public boolean requiresHuman() {
        return true;
    }
}
