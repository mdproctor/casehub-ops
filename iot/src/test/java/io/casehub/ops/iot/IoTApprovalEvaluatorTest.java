package io.casehub.ops.iot;

import io.casehub.desiredstate.api.*;
import io.casehub.iot.api.DeviceClass;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.iot.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTApprovalEvaluatorTest {

    private final IoTApprovalEvaluator evaluator = new IoTApprovalEvaluator();

    @Test
    void physicalDeviceAutoApproves() {
        var spec = new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Living Room Thermostat");
        var node = new DesiredNode(NodeId.of("dev-1"), NodeType.of("physical-device"), spec, true);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void deviceConfigAutoApproves() {
        var spec = new DeviceConfigSpec("dev-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var node = new DesiredNode(NodeId.of("dev-1-config"), NodeType.of("device-config"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void deprovisionAutoApproves() {
        var spec = new DeviceConfigSpec("dev-1", DeviceClass.SWITCH, Map.of("isOn", false));
        var node = new DesiredNode(NodeId.of("dev-1-config"), NodeType.of("device-config"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void nonIoTSpecAutoApproves() {
        NodeSpec unknownSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("x-1"), NodeType.of("unknown"), unknownSpec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }
}
