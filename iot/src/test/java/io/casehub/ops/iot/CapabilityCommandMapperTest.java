package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCommandMapperTest {

    private static final CapabilityCommandMapper.CommandContext CTX =
        new CapabilityCommandMapper.CommandContext("test-provisioner", "corr-001");

    @Test
    void isOn_true_turnOn() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isOn", true, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_TURN_ON);
        assertThat(cmd.targetDeviceId()).isEqualTo("dev-1");
        assertThat(cmd.dispatchedBy()).isEqualTo("test-provisioner");
        assertThat(cmd.correlationId()).isEqualTo("corr-001");
    }

    @Test
    void isOn_false_turnOff() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isOn", false, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_TURN_OFF);
    }

    @Test
    void targetTemperature_reconstructsTemperature() {
        var value = Map.<String, Object>of(
            "value", new BigDecimal("22"), "unit", "CELSIUS");
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "targetTemperature", value, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_TEMPERATURE);
        assertThat(cmd.parameters()).containsEntry("unit", "CELSIUS");
    }

    @Test
    void targetTemperature_integerValueConverted() {
        var value = Map.<String, Object>of("value", 22, "unit", "CELSIUS");
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "targetTemperature", value, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_TEMPERATURE);
    }

    @Test
    void isLocked_true_lock() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isLocked", true, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_LOCK);
    }

    @Test
    void isLocked_false_unlock() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "isLocked", false, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_UNLOCK);
    }

    @Test
    void position_setPosition() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "position", 50, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_POSITION);
        assertThat(cmd.parameters()).containsEntry("position", 50);
    }

    @Test
    void volume_setVolume() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "volume", 70, CTX);
        assertThat(cmd.action()).isEqualTo(DeviceCommand.ACTION_SET_VOLUME);
        assertThat(cmd.parameters()).containsEntry("volume", 70);
    }

    @Test
    void unknownCapability_genericCommand() {
        var cmd = CapabilityCommandMapper.toCommand("dev-1", "speed", 3, CTX);
        assertThat(cmd.action()).isEqualTo("set_speed");
        assertThat(cmd.parameters()).containsEntry("speed", 3);
        assertThat(cmd.dispatchedBy()).isEqualTo("test-provisioner");
    }
}
