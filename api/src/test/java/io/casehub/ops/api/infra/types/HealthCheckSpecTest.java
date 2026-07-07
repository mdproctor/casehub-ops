package io.casehub.ops.api.infra.types;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HealthCheckSpecTest {
    @Test
    void createsValidHealthCheck() {
        var hc = new HealthCheckSpec("/health", 8080, 10, 30);
        assertThat(hc.path()).isEqualTo("/health");
        assertThat(hc.port()).isEqualTo(8080);
        assertThat(hc.initialDelaySeconds()).isEqualTo(10);
        assertThat(hc.periodSeconds()).isEqualTo(30);
    }

    @Test
    void rejectsNullPath() {
        assertThatNullPointerException()
                .isThrownBy(() -> new HealthCheckSpec(null, 8080, 10, 30));
    }
}
