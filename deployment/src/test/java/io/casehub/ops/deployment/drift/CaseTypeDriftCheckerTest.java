package io.casehub.ops.deployment.drift;

import io.casehub.api.model.CaseDefinition;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.CaseTypeNodeSpec;
import io.casehub.ops.deployment.handler.CaseTypeProvisionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaseTypeDriftCheckerTest {

    private CaseTypeDriftChecker checker;
    private ConcurrentHashMap<String, CaseDefinition> definitions;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        definitions = new ConcurrentHashMap<>();
        var handler = new CaseTypeProvisionHandler(definitions);
        checker = new CaseTypeDriftChecker(handler);
    }

    @Test
    void nodeType() {
        assertEquals("case_type", checker.nodeType());
    }

    @Test
    void caseTypePresent() {
        var spec = new CaseTypeNodeSpec("ns", "Incident", "1.0", "Incident Case", "summary", null, null);
        definitions.put(spec.nodeId(), CaseDefinition.builder()
                .namespace("ns")
                .name("Incident")
                .version("1.0")
                .title("Incident Case")
                .summary("summary")
                .build());

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void caseTypeAbsent() {
        var spec = new CaseTypeNodeSpec("ns", "Incident", "1.0", "Incident Case", "summary", null, null);

        assertEquals(NodeStatus.ABSENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void unknownSpecType() {
        var spec = new io.casehub.ops.api.deployment.AgentNodeSpec(
                "agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(), null, "US", "policy", null, List.of());

        assertEquals(NodeStatus.UNKNOWN, checker.check(spec, TENANCY_ID));
    }
}
