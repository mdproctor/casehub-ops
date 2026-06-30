package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.approval.InMemoryPlanStore;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceNodeProvisionerTest {

    private ComplianceNodeProvisioner provisioner;
    private ComplianceFrameworkRegistry registry;
    private ComplianceSpecHashStore specHashStore;
    private DefaultDesiredStateGraphFactory graphFactory;
    // Reference to the ledger stub to verify writes
    private ComplianceEvidenceServiceTest.StubLedgerRepository ledgerRepo;

    @BeforeEach
    void setUp() {
        var collector = new ComplianceEvidenceServiceTest.StubEvidenceCollector(
                "ENCRYPTION_AT_REST", new EvidenceResult.Pass("ok"));
        ledgerRepo = new ComplianceEvidenceServiceTest.StubLedgerRepository();
        var evidenceService = new ComplianceEvidenceService(
                List.of(collector),
                ledgerRepo::save,
                ledgerRepo::findLatest);
        registry = new ComplianceFrameworkRegistry();
        specHashStore = new ComplianceSpecHashStore();
        var approvalEvaluator = new ComplianceApprovalEvaluator();
        var planStore = new InMemoryPlanStore();
        provisioner = new ComplianceNodeProvisioner(evidenceService, registry, specHashStore,
                approvalEvaluator, planStore);
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void provisionCollectsEvidenceAndRegistersControl() {
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Enc", "D",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        var context = new ProvisionContext("default", graphFactory.empty());

        ProvisionResult result = provisioner.provision(node, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(ledgerRepo.saved).isNotNull();
        assertThat(registry.findControl("enc")).isPresent();
        assertThat(specHashStore.hasDrifted(NodeId.of("enc"), spec)).isFalse();
    }

    @Test
    void provisionFailReturnsSuccessButRecordsFail() {
        var failCollector = new ComplianceEvidenceServiceTest.StubEvidenceCollector(
                "ENCRYPTION_AT_REST", new EvidenceResult.Fail("not encrypted"));
        var evidenceService = new ComplianceEvidenceService(
                List.of(failCollector),
                ledgerRepo::save,
                ledgerRepo::findLatest);
        var approvalEvaluator = new ComplianceApprovalEvaluator();
        var planStore = new InMemoryPlanStore();
        provisioner = new ComplianceNodeProvisioner(evidenceService, registry, specHashStore,
                approvalEvaluator, planStore);

        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Enc", "D", List.of(), 30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);

        ProvisionResult result = provisioner.provision(node, new ProvisionContext("default", graphFactory.empty()));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(ledgerRepo.saved.outcome).isEqualTo(EvidenceOutcome.FAIL);
    }

    @Test
    void deprovisionRemovesRegistrationAndHash() {
        var spec = new ComplianceControlSpec(
                "enc", "ENCRYPTION_AT_REST", "Enc", "D",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of());
        var node = new DesiredNode(NodeId.of("enc"), NodeType.of("ENCRYPTION_AT_REST"), spec, false);
        provisioner.provision(node, new ProvisionContext("default", graphFactory.empty()));

        DeprovisionResult result = provisioner.deprovision(node, new DeprovisionContext("default", graphFactory.empty()));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(registry.findControl("enc")).isEmpty();
        assertThat(specHashStore.hasDrifted(NodeId.of("enc"), spec)).isTrue();
    }

    @Test
    void nonComplianceSpecReturnsFailed() {
        var node = new DesiredNode(NodeId.of("x"), NodeType.of("unknown"), new NodeSpec() {}, false);
        var result = provisioner.provision(node, new ProvisionContext("default", graphFactory.empty()));
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }
}
