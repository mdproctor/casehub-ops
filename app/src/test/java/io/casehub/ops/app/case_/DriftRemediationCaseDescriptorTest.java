package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DriftRemediationCaseDescriptorTest {

    @Test
    void buildReturnsCorrectIdentity() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getNamespace()).isEqualTo("ops");
        assertThat(def.getName()).isEqualTo("drift-remediation");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void hasThreeCapabilities() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getCapabilities()).hasSize(3);
        assertThat(def.getCapabilities()).extracting("name")
                .containsExactlyInAnyOrder("classify-drift", "remediate-drift", "escalate-drift");
    }

    @Test
    void hasThreeWorkers() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getWorkers()).hasSize(3);
    }

    @Test
    void hasTwoInternalBindings() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getBindings()).hasSize(2);
    }

    @Test
    void classificationBindingTriggersOnDriftClassification() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        Binding binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-classification-complete"))
                .findFirst().orElseThrow();
        assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    }

    @Test
    void escalationBindingTriggersOnEscalationRequired() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        Binding binding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-escalation-required"))
                .findFirst().orElseThrow();
        assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    }

    @Test
    void hasCompletionPredicate() {
        CaseDefinition def = DriftRemediationCaseDescriptor.build();
        assertThat(def.getCompletion()).isNotNull();
    }
}
