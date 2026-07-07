package io.casehub.ops.app.k8s;

import java.util.concurrent.ConcurrentHashMap;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sClientRegistry {

    private final ConcurrentHashMap<String, KubernetesClient> clients = new ConcurrentHashMap<>();

    public KubernetesClient clientFor(String clusterId) {
        KubernetesClient client = clients.get(clusterId);
        if (client == null) {
            throw new IllegalArgumentException("No client registered for cluster: " + clusterId);
        }
        return client;
    }

    public void register(String clusterId, String apiUrl) {
        Config config = new ConfigBuilder()
                .withMasterUrl(apiUrl)
                .withTrustCerts(true)
                .build();
        KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(config)
                .build();
        KubernetesClient existing = clients.putIfAbsent(clusterId, client);
        if (existing != null) {
            client.close();
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
