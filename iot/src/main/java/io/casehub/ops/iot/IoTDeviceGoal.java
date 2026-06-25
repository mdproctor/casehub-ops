package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceClass;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record IoTDeviceGoal(
    String deviceId,
    DeviceClass deviceClass,
    String label,
    Boolean physical,
    Map<String, Object> config,
    List<String> dependsOn
) {
    public IoTDeviceGoal {
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        Objects.requireNonNull(label, "label required");
        physical = physical != null ? physical : true;
        if (config != null && config.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "null config values not permitted — remove the key instead");
        }
        config = config != null ? Map.copyOf(config) : Map.of();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
