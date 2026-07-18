package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceActualStateAdapterTest {

    private ComplianceActualStateAdapter adapter;
    private ComplianceSpecHashStore specHashStore;
    private StubEvidenceService evidenceService;
    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        specHashStore = new ComplianceSpecHashStore();
        evidenceService = new StubEvidenceService();
        graphFactory = new DefaultDesiredStateGraphFactory();
        adapter = new ComplianceActualStateAdapter(evidenceService, specHashStore);
    }

    @Test
    void absentWhenNoEvidence() {
        evidenceService.nextStatus = ControlEvidenceStatus.absent("enc", "ENCRYPTION_AT_REST", 30);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());
        ActualState actual = adapter.readActual(graph, "default");
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void presentWhenFreshPassAndHashMatches() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.PASS,
                Instant.now(), 30, false, NodeStatus.PRESENT);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        specHashStore.record(NodeId.of("enc"), spec);
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());
        ActualState actual = adapter.readActual(graph, "default");
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void driftedWhenFreshPassButHashChanged() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.PASS,
                Instant.now(), 30, false, NodeStatus.PRESENT);
        var specOld = minimalSpec("enc", "ENCRYPTION_AT_REST");
        specHashStore.record(NodeId.of("enc"), specOld);
        var specNew = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "FILE_EXISTENCE", "Changed", "D", List.of(), 30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), specNew, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());
        ActualState actual = adapter.readActual(graph, "default");
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftedWhenEvidenceFails() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.FAIL,
                Instant.now(), 30, false, NodeStatus.DRIFTED);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());
        ActualState actual = adapter.readActual(graph, "default");
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftedWhenEvidenceUnavailable() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.UNAVAILABLE,
                Instant.now(), 30, false, NodeStatus.DRIFTED);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());
        ActualState actual = adapter.readActual(graph, "default");
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftedWhenEvidenceStale() {
        evidenceService.nextStatus = new ControlEvidenceStatus(
                "enc", "ENCRYPTION_AT_REST", EvidenceOutcome.PASS,
                Instant.now().minus(60, ChronoUnit.DAYS), 30, true, NodeStatus.DRIFTED);
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST");
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, io.casehub.desiredstate.api.HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());
        ActualState actual = adapter.readActual(graph, "default");
        assertThat(actual.statuses().get(NodeId.of("enc"))).isEqualTo(NodeStatus.DRIFTED);
    }

    private ComplianceControlSpec minimalSpec(String id, String type) {
        return new ComplianceControlSpec(id, type, "FILE_EXISTENCE", "Title", "Desc", List.of(), 30, false, Map.of());
    }

    // Test stub that allows controlled evidenceStatus returns
    static class StubEvidenceService extends ComplianceEvidenceService {
        ControlEvidenceStatus nextStatus;

        StubEvidenceService() {
            super(List.of(), (entry, tenancyId) -> {}, (controlId, tenancyId) -> List.of());
        }

        @Override
        public ControlEvidenceStatus evidenceStatus(ComplianceControlSpec spec, String tenancyId) {
            return nextStatus;
        }
    }
}
