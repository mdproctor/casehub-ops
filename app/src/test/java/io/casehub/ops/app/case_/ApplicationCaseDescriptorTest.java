package io.casehub.ops.app.case_;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.SubCaseTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApplicationCaseDescriptorTest {

    @Test
    void buildReturnsCorrectIdentity() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        assertThat(def.getNamespace()).isEqualTo("ops");
        assertThat(def.getName()).isEqualTo("application-lifecycle");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void hasSixBindings() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        assertThat(def.getBindings()).hasSize(6);
    }

    @Test
    void driftBindingHasCorrectTriggerAndTarget() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        Binding driftBinding = def.getBindings().stream()
                .filter(b -> b.getName().equals("on-drift-detected"))
                .findFirst().orElseThrow();

        assertThat(driftBinding.getOn()).isInstanceOf(ContextChangeTrigger.class);
        assertThat(driftBinding.target()).isInstanceOf(SubCaseTarget.class);
        var subCaseTarget = (SubCaseTarget) driftBinding.target();
        assertThat(subCaseTarget.subCase().namespace()).isEqualTo("ops");
        assertThat(subCaseTarget.subCase().name()).isEqualTo("drift-remediation");
        assertThat(subCaseTarget.subCase().version()).isEqualTo("1.0");
    }

    @Test
    void allBindingNamesAreUnique() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        var names = def.getBindings().stream().map(Binding::getName).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void allBindingsTargetSubCases() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        for (Binding binding : def.getBindings()) {
            assertThat(binding.target())
                    .as("Binding '%s' must target a SubCase", binding.getName())
                    .isInstanceOf(SubCaseTarget.class);
        }
    }

    @Test
    void allSubCasesDoNotWaitForCompletion() {
        CaseDefinition def = ApplicationCaseDescriptor.build();
        for (Binding binding : def.getBindings()) {
            var target = (SubCaseTarget) binding.target();
            assertThat(target.subCase().waitForCompletion())
                    .as("Binding '%s' SubCase must not wait for completion", binding.getName())
                    .isFalse();
        }
    }
}
