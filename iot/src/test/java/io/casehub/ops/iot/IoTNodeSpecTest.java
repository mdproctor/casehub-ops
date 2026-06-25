package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceClass;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.IoTNodeSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTNodeSpecTest {

    @Test
    void physicalDeviceSpec_validFields() {
        var spec = new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Living Room");
        assertThat(spec.deviceId()).isEqualTo("dev-1");
        assertThat(spec.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(spec.label()).isEqualTo("Living Room");
        assertThat(spec).isInstanceOf(IoTNodeSpec.class);
    }

    @Test
    void physicalDeviceSpec_nullDeviceIdThrows() {
        assertThatThrownBy(() -> new PhysicalDeviceSpec(null, DeviceClass.THERMOSTAT, "label"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("deviceId");
    }

    @Test
    void deviceConfigSpec_validFields() {
        var caps = Map.<String, Object>of("isOn", true, "brightness", 80);
        var spec = new DeviceConfigSpec("dev-1", DeviceClass.LIGHT, caps);
        assertThat(spec.deviceId()).isEqualTo("dev-1");
        assertThat(spec.desiredCapabilities()).containsEntry("isOn", true);
    }

    @Test
    void deviceConfigSpec_nullCapabilitiesMapThrows() {
        assertThatThrownBy(() -> new DeviceConfigSpec("dev-1", DeviceClass.LIGHT, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("desiredCapabilities");
    }

    @Test
    void deviceConfigSpec_nullValueInCapabilitiesThrows() {
        var caps = new HashMap<String, Object>();
        caps.put("brightness", null);
        assertThatThrownBy(() -> new DeviceConfigSpec("dev-1", DeviceClass.LIGHT, caps))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null capability values");
    }

    @Test
    void deviceConfigSpec_capabilitiesAreImmutable() {
        var caps = new HashMap<String, Object>();
        caps.put("isOn", true);
        var spec = new DeviceConfigSpec("dev-1", DeviceClass.SWITCH, caps);
        assertThatThrownBy(() -> spec.desiredCapabilities().put("extra", false))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
