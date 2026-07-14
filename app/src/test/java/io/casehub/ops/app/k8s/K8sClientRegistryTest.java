package io.casehub.ops.app.k8s;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class K8sClientRegistryTest {

    private K8sClientRegistry registry;

    @AfterEach
    void cleanup() {
        if (registry != null) {registry.shutdown();}
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
        AtomicBoolean      called   = new AtomicBoolean(false);
        CredentialResolver resolver = ref -> {
            called.set(true);
            return Map.of();
        };
        registry = new K8sClientRegistry(resolver);

        registry.register("c1", "https://localhost:6443", null, true);

        assertThat(called.get()).isFalse();
        assertThat(registry.clientFor("c1")).isNotNull();
    }

    @Test
    void registerWithBlankCredentialRefSkipsResolution() {
        AtomicBoolean      called   = new AtomicBoolean(false);
        CredentialResolver resolver = ref -> {
            called.set(true);
            return Map.of();
        };
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

    @Test
    void registerParsesExpiresAt() {
        Instant            expiry   = Instant.now().plus(Duration.ofHours(1));
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok", "expires-at", expiry.toString());
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        assertThat(registry.clientFor("c1")).isNotNull();
    }

    @Test
    void registerWithoutExpiresAtStoresNull() {
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        assertThat(registry.clientFor("c1")).isNotNull();
    }

    @Test
    void reRegisterUpdatesMetadataWithoutReplacingClient() {
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds-v1", true);
        KubernetesClient firstClient = registry.clientFor("c1");

        registry.register("c1", "https://localhost:6443", "creds-v2", true);
        KubernetesClient secondClient = registry.clientFor("c1");

        assertThat(secondClient).isSameAs(firstClient);
    }

    @Test
    void refreshReplacesClient() {
        AtomicInteger resolveCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            resolveCount.incrementAndGet();
            return Map.of("bearer-token", "tok-" + resolveCount.get());
        };
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        KubernetesClient before = registry.clientFor("c1");

        registry.refreshClient("c1");
        KubernetesClient after = registry.clientFor("c1");

        assertThat(after).isNotSameAs(before);
        assertThat(resolveCount.get()).isEqualTo(2);
    }

    @Test
    void refreshPreservesRegistration() {
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);

        registry.refreshClient("c1");

        assertThat(registry.clientFor("c1")).isNotNull();
        assertThat(registry.clientFor("c1").getConfiguration().getOauthToken()).isEqualTo("tok");
    }

    @Test
    void refreshUnknownClusterThrows() {
        registry = new K8sClientRegistry(ref -> Map.of());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.refreshClient("unknown"))
                .withMessageContaining("unknown");
    }

    @Test
    void proactiveScanRefreshesApproachingExpiry() {
        Instant       nearExpiry   = Instant.now().plus(Duration.ofMinutes(3));
        AtomicInteger resolveCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            resolveCount.incrementAndGet();
            return Map.of("bearer-token", "tok", "expires-at", nearExpiry.toString());
        };
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        int countAfterRegister = resolveCount.get();

        registry.checkExpiring();

        assertThat(resolveCount.get()).isGreaterThan(countAfterRegister);
    }

    @Test
    void proactiveScanSkipsNullExpiry() {
        AtomicInteger resolveCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            resolveCount.incrementAndGet();
            return Map.of("bearer-token", "tok");
        };
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        int countAfterRegister = resolveCount.get();

        registry.checkExpiring();

        assertThat(resolveCount.get()).isEqualTo(countAfterRegister);
    }

    @Test
    void proactiveScanSkipsDistantExpiry() {
        Instant       farExpiry    = Instant.now().plus(Duration.ofHours(2));
        AtomicInteger resolveCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            resolveCount.incrementAndGet();
            return Map.of("bearer-token", "tok", "expires-at", farExpiry.toString());
        };
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        int countAfterRegister = resolveCount.get();

        registry.checkExpiring();

        assertThat(resolveCount.get()).isEqualTo(countAfterRegister);
    }

    @Test
    void refreshCoalescesConcurrentCalls() throws Exception {
        java.util.concurrent.CountDownLatch refreshStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger resolveCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            int count = resolveCount.incrementAndGet();
            if (count > 1) {
                refreshStarted.countDown();
                try { proceed.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Map.of("bearer-token", "tok-" + count);
        };
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);
        int countAfterRegister = resolveCount.get();

        java.util.concurrent.CompletableFuture<Void> first = java.util.concurrent.CompletableFuture.runAsync(() -> registry.refreshClient("c1"));
        refreshStarted.await(5, java.util.concurrent.TimeUnit.SECONDS);
        java.util.concurrent.CompletableFuture<Void> second = java.util.concurrent.CompletableFuture.runAsync(() -> registry.refreshClient("c1"));

        proceed.countDown();
        java.util.concurrent.CompletableFuture.allOf(first, second).join();

        assertThat(resolveCount.get()).isEqualTo(countAfterRegister + 1);
    }

    @Test
    void withRetryOn401ReturnsResultOnSuccess() {
        registry = new K8sClientRegistry(ref -> Map.of());
        registry.register("c1", "https://localhost:6443");

        String result = registry.withRetryOn401("c1", client -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void withRetryOn401RefreshesAndRetriesOn401() {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger resolveCount = new AtomicInteger();
        CredentialResolver resolver = ref -> {
            resolveCount.incrementAndGet();
            return Map.of("bearer-token", "tok-" + resolveCount.get());
        };
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);

        String result = registry.withRetryOn401("c1", client -> {
            if (callCount.getAndIncrement() == 0) {
                throw new KubernetesClientException("Unauthorized", 401, null);
            }
            return "retried-ok";
        });

        assertThat(result).isEqualTo("retried-ok");
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(resolveCount.get()).isEqualTo(2);
    }

    @Test
    void withRetryOn401PropagatesNon401KubernetesClientException() {
        registry = new K8sClientRegistry(ref -> Map.of());
        registry.register("c1", "https://localhost:6443");

        assertThatThrownBy(() -> registry.withRetryOn401("c1", client -> {
            throw new KubernetesClientException("Forbidden", 403, null);
        })).isInstanceOf(KubernetesClientException.class)
           .hasMessageContaining("Forbidden");
    }

    @Test
    void withRetryOn401PropagatesNonK8sExceptions() {
        registry = new K8sClientRegistry(ref -> Map.of());
        registry.register("c1", "https://localhost:6443");

        assertThatThrownBy(() -> registry.withRetryOn401("c1", client -> {
            throw new IllegalStateException("something else");
        })).isInstanceOf(IllegalStateException.class)
           .hasMessageContaining("something else");
    }

    @Test
    void withRetryOn401PropagatesSecondFailure() {
        AtomicInteger callCount = new AtomicInteger();
        CredentialResolver resolver = ref -> Map.of("bearer-token", "tok");
        registry = new K8sClientRegistry(resolver);
        registry.register("c1", "https://localhost:6443", "creds", true);

        assertThatThrownBy(() -> registry.withRetryOn401("c1", client -> {
            callCount.incrementAndGet();
            throw new KubernetesClientException("Unauthorized", 401, null);
        })).isInstanceOf(KubernetesClientException.class);

        assertThat(callCount.get()).isEqualTo(2);
    }
}
