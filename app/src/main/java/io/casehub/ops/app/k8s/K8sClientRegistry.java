package io.casehub.ops.app.k8s;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class K8sClientRegistry {

    private static final Logger LOG = Logger.getLogger(K8sClientRegistry.class.getName());

    private final ConcurrentHashMap<String, KubernetesClient> clients = new ConcurrentHashMap<>();
    private final CredentialResolver                          credentialResolver;

    @Inject
    public K8sClientRegistry(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    public KubernetesClient clientFor(String clusterId) {
        KubernetesClient client = clients.get(clusterId);
        if (client == null) {
            throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
        }
        return client;
    }

    public void register(String clusterId, String apiUrl) {
        register(clusterId, apiUrl, null, true);
    }

    public void register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts) {
        Config config = new ConfigBuilder()
                                .withMasterUrl(apiUrl)
                                .withTrustCerts(trustCerts)
                                .build();

        if (credentialRef != null && !credentialRef.isBlank()) {
            Map<String, String> creds = credentialResolver.resolve(credentialRef);
            if (creds.isEmpty()) {
                LOG.warning("Credential reference '" + credentialRef
                            + "' resolved to empty map — possible misconfiguration. Falling back to auto-detection.");
            } else {
                applyCredentials(config, creds);
            }
        }

        KubernetesClient client = new KubernetesClientBuilder()
                                          .withConfig(config)
                                          .build();
        KubernetesClient existing = clients.putIfAbsent(clusterId, client);
        if (existing != null) {
            client.close();
        }
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

    public void deregister(String clusterId) {
        KubernetesClient client = clients.remove(clusterId);
        if (client != null) {
            client.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(KubernetesClient::close);
        clients.clear();
    }
}
