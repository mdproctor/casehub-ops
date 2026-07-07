package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.ops.api.approval.ApprovalDecision;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceApprovalEvaluatorTest {

    private final ComplianceApprovalEvaluator evaluator = new ComplianceApprovalEvaluator();

    @Test
    void complianceControlAutoApproves() {
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "FILE_EXISTENCE", "Enc", "D",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void deprovisionAutoApproves() {
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "FILE_EXISTENCE", "Enc", "D",
                List.of(), 30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);

        var decision = evaluator.evaluate(node, StepAction.DEPROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }

    @Test
    void nonComplianceSpecAutoApproves() {
        NodeSpec unknownSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("x-1"), NodeType.of("unknown"), unknownSpec, false);

        var decision = evaluator.evaluate(node, StepAction.PROVISION, "tenant-1");

        assertThat(decision).isInstanceOf(ApprovalDecision.AutoApproved.class);
    }
}
