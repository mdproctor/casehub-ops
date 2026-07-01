package io.casehub.ops.api.iot;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PhysicalDeviceSpecTest {
    @Test
    void requiresHuman_alwaysTrue() {
        var spec = new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label");
        assertThat(spec.requiresHuman()).isTrue();
    }
}
