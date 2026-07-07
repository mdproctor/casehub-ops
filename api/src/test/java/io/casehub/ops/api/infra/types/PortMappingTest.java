package io.casehub.ops.api.infra.types;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PortMappingTest {
    @Test
    void createsValidPortMapping() {
        var pm = new PortMapping(8080, 80, "TCP");
        assertThat(pm.containerPort()).isEqualTo(8080);
        assertThat(pm.servicePort()).isEqualTo(80);
        assertThat(pm.protocol()).isEqualTo("TCP");
    }

    @Test
    void rejectsNullProtocol() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PortMapping(8080, 80, null));
    }
}
