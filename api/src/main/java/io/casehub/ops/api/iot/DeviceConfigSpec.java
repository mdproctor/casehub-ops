package io.casehub.ops.api.iot;

import io.casehub.iot.api.DeviceClass;
import java.util.Map;
import java.util.Objects;

public record DeviceConfigSpec(
    String deviceId,
    DeviceClass deviceClass,
    Map<String, Object> desiredCapabilities
) implements IoTNodeSpec {
    public DeviceConfigSpec {
        Objects.requireNonNull(desiredCapabilities, "desiredCapabilities required");
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        if (desiredCapabilities.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "null capability values not permitted — remove the key instead");
        }
        desiredCapabilities = Map.copyOf(desiredCapabilities);
    }
}
