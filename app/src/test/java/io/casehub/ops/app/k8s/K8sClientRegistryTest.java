package io.casehub.ops.app.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class K8sClientRegistryTest {

    private final K8sClientRegistry registry = new K8sClientRegistry();

    @AfterEach
    void cleanup() {
        registry.shutdown();
    }

    @Test
    void registerAndRetrieveClient() {
        registry.register("ops-prod", "https://localhost:6443");
        KubernetesClient client = registry.clientFor("ops-prod");
        assertThat(client).isNotNull();
        assertThat(client.getConfiguration().getMasterUrl())
                .contains("localhost:6443");
    }

    @Test
    void clientForUnknownClusterThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("unknown"))
                .withMessageContaining("unknown");
    }

    @Test
    void deregisterClosesClient() {
        registry.register("ops-staging", "https://localhost:6443");
        KubernetesClient client = registry.clientFor("ops-staging");
        assertThat(client).isNotNull();

        registry.deregister("ops-staging");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("ops-staging"));
    }

    @Test
    void shutdownClosesAllClients() {
        registry.register("c1", "https://localhost:6443");
        registry.register("c2", "https://localhost:6444");
        registry.shutdown();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c2"));
    }
}
