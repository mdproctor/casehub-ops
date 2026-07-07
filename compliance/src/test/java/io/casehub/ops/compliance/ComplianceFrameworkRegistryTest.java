package io.casehub.ops.compliance;

import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceFrameworkRegistryTest {

    private ComplianceFrameworkRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ComplianceFrameworkRegistry();
    }

    @Test
    void registerAndFindControl() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        registry.register(spec);
        assertThat(registry.findControl("enc")).isPresent();
        assertThat(registry.findControl("enc").get().controlId()).isEqualTo("enc");
    }

    @Test
    void controlsForFramework() {
        var spec1 = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        var spec2 = minimalSpec("log", "LOG_RETENTION",
                List.of(new FrameworkMapping("SOC2", "CC7.2")));
        registry.register(spec1);
        registry.register(spec2);

        assertThat(registry.controlsForFramework("SOC2")).hasSize(2);
        assertThat(registry.controlsForFramework("GDPR")).hasSize(1);
        assertThat(registry.controlsForFramework("DORA")).isEmpty();
    }

    @Test
    void frameworksForControl() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);
        assertThat(registry.frameworksForControl("enc")).hasSize(2);
    }

    @Test
    void registeredFrameworks() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"), new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);
        assertThat(registry.registeredFrameworks()).containsExactlyInAnyOrder("SOC2", "GDPR");
    }

    @Test
    void deregisterRemovesControl() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        registry.register(spec);
        registry.deregister("enc");
        assertThat(registry.findControl("enc")).isEmpty();
        assertThat(registry.controlsForFramework("SOC2")).isEmpty();
    }

    @Test
    void findControlReturnsEmptyForUnknown() {
        assertThat(registry.findControl("nonexistent")).isEmpty();
    }

    private ComplianceControlSpec minimalSpec(String id, String type, List<FrameworkMapping> frameworks) {
        return new ComplianceControlSpec(id, type, "FILE_EXISTENCE", "T", "D", frameworks, 30, false, Map.of());
    }
}
