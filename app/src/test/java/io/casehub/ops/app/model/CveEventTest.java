package io.casehub.ops.app.model;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CveEventTest {
    @Test
    void createsValidCveEvent() {
        var cve = new CveEvent("CVE-2024-1234", CveSeverity.CRITICAL,
                "quay.io/app:1.0", List.of("inventory"), "1.0.1", "trivy");
        assertThat(cve.cveId()).isEqualTo("CVE-2024-1234");
        assertThat(cve.severity()).isEqualTo(CveSeverity.CRITICAL);
        assertThat(cve.affectedServices()).containsExactly("inventory");
    }

    @Test
    void allowsNullFixedInTag() {
        var cve = new CveEvent("CVE-2024-1234", CveSeverity.HIGH,
                "quay.io/app:1.0", List.of(), null, "grype");
        assertThat(cve.fixedInTag()).isNull();
    }
}
