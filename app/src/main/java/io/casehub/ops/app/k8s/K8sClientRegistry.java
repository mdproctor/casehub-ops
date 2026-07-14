package io.casehub.ops.app.k8s;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class K8sClientRegistry {
    private static final Logger                                 LOG     = Logger.getLogger(K8sClientRegistry.class.getName());
    private final        ConcurrentHashMap<String, ClientEntry> clients = new ConcurrentHashMap<>();
    private final        CredentialResolver                     credentialResolver;
    private final        ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>> refreshesInFlight = new ConcurrentHashMap<>();
    @Inject
    jakarta.enterprise.event.Event<CredentialRefreshedEvent> credentialRefreshedEvent;


    @Inject
    public K8sClientRegistry(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    public KubernetesClient clientFor(String clusterId) {
        ClientEntry entry = clients.get(clusterId);
        if (entry == null) {
            throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
        }
        return entry.client();
    }

    public <T> T withRetryOn401(String clusterId, java.util.function.Function<KubernetesClient, T> operation) {
        try {
            return operation.apply(clientFor(clusterId));
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            if (e.getCode() == 401) {
                LOG.info("Received 401 for cluster " + clusterId + " — refreshing credentials and retrying");
                refreshClient(clusterId);
                return operation.apply(clientFor(clusterId));
            }
            throw e;
        }
    }


    public void register(String clusterId, String apiUrl) {
        register(clusterId, apiUrl, null, true);
    }

    public void register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts) {
        Map<String, String> creds = Map.of();
        if (credentialRef != null && !credentialRef.isBlank()) {
            creds = credentialResolver.resolve(credentialRef);
            if (creds.isEmpty()) {
                LOG.warning("Credential reference '" + credentialRef
                            + "' resolved to empty map — possible misconfiguration. Falling back to auto-detection.");
            }
        }

        String  expiresAtStr = creds.get("expires-at");
        Instant expiresAt    = expiresAtStr != null ? Instant.parse(expiresAtStr) : null;

        Map<String, String> finalCreds = creds;
        clients.compute(clusterId, (id, existing) -> {
            if (existing != null) {
                return new ClientEntry(existing.client(), apiUrl, credentialRef, trustCerts, expiresAt);
            }
            Config config = new ConfigBuilder()
                                    .withMasterUrl(apiUrl)
                                    .withTrustCerts(trustCerts)
                                    .build();
            if (!finalCreds.isEmpty()) {
                applyCredentials(config, finalCreds);
            }
            KubernetesClient client = new KubernetesClientBuilder()
                                              .withConfig(config)
                                              .build();
            return new ClientEntry(client, apiUrl, credentialRef, trustCerts, expiresAt);
        });
    }

    private void applyCredentials(Config config, Map<String, String> creds) {
        String bearerToken = creds.get("bearer-token");
        if (bearerToken != null) {
            config.setOauthToken(bearerToken);
            return;
        }
        String user     = creds.get("user");
        String password = creds.get("password");
        if (user != null && password != null) {
            config.setUsername(user);
            config.setPassword(password);
            return;
        }
        String apiKey = creds.get("api-key");
        if (apiKey != null) {
            config.setCustomHeaders(Map.of("Authorization", "ApiKey " + apiKey));
        }
    }


    public void refreshClient(String clusterId) {
        java.util.concurrent.CompletableFuture<Void> newFuture = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Void> future = refreshesInFlight.putIfAbsent(clusterId, newFuture);

        if (future == null) {
            future = newFuture;
            try {
                doRefresh(clusterId);
                newFuture.complete(null);
            } catch (Exception e) {
                newFuture.completeExceptionally(e);
            } finally {
                refreshesInFlight.remove(clusterId, newFuture);
            }
        }

        try {
            future.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private void doRefresh(String clusterId) {
        ClientEntry existing = clients.get(clusterId);
        if (existing == null) {
            throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
        }

        Map<String, String> creds        = credentialResolver.resolve(existing.credentialRef());
        String              expiresAtStr = creds.get("expires-at");
        Instant             newExpiresAt = expiresAtStr != null ? Instant.parse(expiresAtStr) : null;

        Config config = new ConfigBuilder()
                                .withMasterUrl(existing.apiUrl())
                                .withTrustCerts(existing.trustCerts())
                                .build();
        if (!creds.isEmpty()) {
            applyCredentials(config, creds);
        }
        KubernetesClient newClient = new KubernetesClientBuilder()
                                             .withConfig(config)
                                             .build();

        ClientEntry newEntry = new ClientEntry(newClient, existing.apiUrl(), existing.credentialRef(),
                                               existing.trustCerts(), newExpiresAt);
        ClientEntry old = clients.put(clusterId, newEntry);

        if (credentialRefreshedEvent != null) {
            credentialRefreshedEvent.fire(new CredentialRefreshedEvent(clusterId));
        }

        if (old != null) {
            old.client().close();
        }
    }

    @io.quarkus.scheduler.Scheduled(every = "60s")
    void checkExpiring() {
        for (var entry : clients.entrySet()) {
            try {
                Instant expiresAt = entry.getValue().expiresAt();
                if (expiresAt == null) {continue;}
                java.time.Duration remaining = java.time.Duration.between(Instant.now(), expiresAt);
                if (remaining.toMinutes() < 5) {
                    LOG.info("Credential for cluster " + entry.getKey() + " expiring in "
                             + remaining.toSeconds() + "s — refreshing");
                    refreshClient(entry.getKey());
                }
            } catch (Exception e) {
                LOG.warning("Failed to refresh credential for cluster " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    public void deregister(String clusterId) {
        ClientEntry entry = clients.remove(clusterId);
        if (entry != null) {
            entry.client().close();
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(entry -> entry.client().close());
        clients.clear();
    }

    record ClientEntry(KubernetesClient client, String apiUrl, String credentialRef,
                       boolean trustCerts, Instant expiresAt) {}
}
