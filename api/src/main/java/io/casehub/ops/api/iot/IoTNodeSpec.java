package io.casehub.ops.api.iot;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.iot.api.DeviceClass;

public sealed interface IoTNodeSpec extends NodeSpec
    permits PhysicalDeviceSpec, DeviceConfigSpec {
    String deviceId();
    DeviceClass deviceClass();
}
