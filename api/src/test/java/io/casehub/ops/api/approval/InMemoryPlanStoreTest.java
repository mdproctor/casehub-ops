package io.casehub.ops.api.approval;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.StepAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPlanStoreTest {

    private InMemoryPlanStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPlanStore();
    }

    private ApprovalPlan testPlan() {
        return new ApprovalPlan(
                NodeId.of("node-1"), StepAction.PROVISION, RiskClassification.HIGH,
                "Test plan", "tenant-1", new StubSpec(), null);
    }

    @Test
    void storeAndRetrieve() {
        var plan = testPlan();
        String ref = store.store(plan);
        assertThat(ref).isNotNull().isNotBlank();
        assertThat(store.retrieve(ref)).contains(plan);
    }

    @Test
    void retrieveMissingKeyReturnsEmpty() {
        assertThat(store.retrieve("nonexistent")).isEmpty();
    }

    @Test
    void removeDeletesPlan() {
        String ref = store.store(testPlan());
        store.remove(ref);
        assertThat(store.retrieve(ref)).isEmpty();
    }

    @Test
    void removeNonexistentKeyIsNoOp() {
        store.remove("nonexistent"); // should not throw
    }

    @Test
    void eachStoreGeneratesUniqueReference() {
        String ref1 = store.store(testPlan());
        String ref2 = store.store(testPlan());
        assertThat(ref1).isNotEqualTo(ref2);
    }

    private record StubSpec() implements NodeSpec {}
}
