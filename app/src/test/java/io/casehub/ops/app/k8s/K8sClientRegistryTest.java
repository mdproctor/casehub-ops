package io.casehub.ops.app.k8s;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class K8sClientRegistryTest {

    private K8sClientRegistry registry;

    @AfterEach
    void cleanup() {
        if (registry != null) registry.shutdown();
    }

    @Test
    void registerWithBearerToken() {
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok123");
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", "prod-creds", false);

        KubernetesClient client = registry.clientFor("c1");
        assertThat(client.getConfiguration().getOauthToken()).isEqualTo("tok123");
        assertThat(client.getConfiguration().isTrustCerts()).isFalse();
    }

    @Test
    void registerWithUserPassword() {
        CredentialResolver resolver = ref -> Map.of("user", "admin", "password", "secret");
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", "dev-creds", true);

        KubernetesClient client = registry.clientFor("c1");
        assertThat(client.getConfiguration().getUsername()).isEqualTo("admin");
        assertThat(client.getConfiguration().getPassword()).isEqualTo("secret");
    }

    @Test
    void registerWithApiKey() {
        CredentialResolver resolver = ref -> Map.of("api-key", "key-abc");
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", "api-creds", true);

        KubernetesClient client = registry.clientFor("c1");
        assertThat(client.getConfiguration().getCustomHeaders())
                .containsEntry("Authorization", "ApiKey key-abc");
    }

    @Test
    void registerWithNullCredentialRefSkipsResolution() {
        AtomicBoolean called = new AtomicBoolean(false);
        CredentialResolver resolver = ref -> { called.set(true); return Map.of(); };
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", null, true);

        assertThat(called.get()).isFalse();
        assertThat(registry.clientFor("c1")).isNotNull();
    }

    @Test
    void registerWithBlankCredentialRefSkipsResolution() {
        AtomicBoolean called = new AtomicBoolean(false);
        CredentialResolver resolver = ref -> { called.set(true); return Map.of(); };
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", "  ", true);

        assertThat(called.get()).isFalse();
    }

    @Test
    void registerLegacyTwoArgDelegates() {
        CredentialResolver resolver = ref -> Map.of();
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443");

        KubernetesClient client = registry.clientFor("c1");
        assertThat(client).isNotNull();
        assertThat(client.getConfiguration().isTrustCerts()).isTrue();
    }

    @Test
    void clientForUnknownClusterThrows() {
        registry = new K8sClientRegistry(ref -> Map.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("unknown"))
                .withMessageContaining("unknown");
    }

    @Test
    void deregisterClosesClient() {
        registry = new K8sClientRegistry(ref -> Map.of());
        registry.register("c1", "https://localhost:6443");
        assertThat(registry.clientFor("c1")).isNotNull();

        registry.deregister("c1");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c1"));
    }

    @Test
    void shutdownClosesAllClients() {
        registry = new K8sClientRegistry(ref -> Map.of());
        registry.register("c1", "https://localhost:6443");
        registry.register("c2", "https://localhost:6444");

        registry.shutdown();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.clientFor("c2"));
    }

    @Test
    void trustCertsFalseApplied() {
        registry = new K8sClientRegistry(ref -> Map.of());

        registry.register("c1", "https://localhost:6443", null, false);

        assertThat(registry.clientFor("c1").getConfiguration().isTrustCerts()).isFalse();
    }
}
