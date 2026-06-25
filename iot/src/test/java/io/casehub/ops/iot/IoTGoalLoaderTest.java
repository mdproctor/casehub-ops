package io.casehub.ops.iot;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTGoalLoaderTest {

    private IoTGoalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new IoTGoalLoader();
    }

    @Test
    void loadSingleFile() {
        IoTGoals goals = loader.load("iot-topology-simple.yaml");
        assertThat(goals.tenancyId()).isEqualTo("test-tenant");
        assertThat(goals.devices()).hasSize(1);
        assertThat(goals.devices().getFirst().deviceId()).isEqualTo("switch-1");
        assertThat(goals.devices().getFirst().deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void loadDirectory() throws URISyntaxException {
        var dirUrl = getClass().getClassLoader().getResource("iot-topology-dir");
        IoTGoals goals = loader.loadDirectory(Path.of(dirUrl.toURI()).toString());
        assertThat(goals.devices()).hasSize(2);
    }

    @Test
    void mergeDuplicateDeviceIdThrows() {
        IoTGoals a = loader.load("iot-topology-simple.yaml");
        IoTGoals b = loader.load("iot-topology-simple.yaml");
        assertThatThrownBy(() -> IoTGoalLoader.merge(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("switch-1");
    }

    @Test
    void mergeInconsistentTenancyIdThrows() {
        var a = new IoTGoals("tenant-a", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.SWITCH, "Switch", true, Map.of("isOn", true), List.of())));
        var b = new IoTGoals("tenant-b", List.of(
            new IoTDeviceGoal("dev-2", DeviceClass.LIGHT, "Light", true, Map.of("isOn", false), List.of())));
        assertThatThrownBy(() -> IoTGoalLoader.merge(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Inconsistent tenancyId");
    }
}
