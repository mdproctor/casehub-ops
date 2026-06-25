package io.casehub.ops.iot;

import java.util.List;
import java.util.Objects;

public record IoTGoals(String tenancyId, List<IoTDeviceGoal> devices) {
    public IoTGoals {
        Objects.requireNonNull(tenancyId, "tenancyId required");
        devices = List.copyOf(devices);
    }
}
