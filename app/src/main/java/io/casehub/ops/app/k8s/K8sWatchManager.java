package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class K8sWatchManager {

    private static final Logger LOG              = Logger.getLogger(K8sWatchManager.class.getName());
    private static final String MANAGED_BY_LABEL = "managed-by";
    private static final String MANAGED_BY_VALUE = "casehub-ops";

    private final K8sClientRegistry                      clientRegistry;
    private final KubernetesEventSource                  eventSource;
    private final ConcurrentHashMap<String, List<Watch>> activeWatches = new ConcurrentHashMap<>();

    @Inject
    public K8sWatchManager(K8sClientRegistry clientRegistry, KubernetesEventSource eventSource) {
        this.clientRegistry = clientRegistry;
        this.eventSource    = eventSource;
    }

    public void startWatching(String clusterId, String namespace) {
        String watchKey = clusterId + ":" + namespace;
        if (activeWatches.containsKey(watchKey)) {
            return;
        }

        KubernetesClient      client  = clientRegistry.clientFor(clusterId);
        java.util.List<Watch> watches = new java.util.ArrayList<>();

        watches.add(client.apps().deployments()
                          .inNamespace(namespace)
                          .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                          .watch(driftWatcher(clusterId, "deployment")));

        watches.add(client.services()
                          .inNamespace(namespace)
                          .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                          .watch(driftWatcher(clusterId, "service")));

        watches.add(client.configMaps()
                          .inNamespace(namespace)
                          .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                          .watch(driftWatcher(clusterId, "configmap")));

        watches.add(client.network().v1().ingresses()
                          .inNamespace(namespace)
                          .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                          .watch(driftWatcher(clusterId, "ingress")));

        java.util.List<Watch> existing = activeWatches.putIfAbsent(watchKey, watches);
        if (existing != null) {
            watches.forEach(Watch::close);
            return;
        }
        LOG.info("Started watching " + watchKey + " — 4 resource types");
    }

    public void stopWatching(String clusterId) {
        activeWatches.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(clusterId + ":")) {
                entry.getValue().forEach(Watch::close);
                return true;
            }
            return false;
        });
    }

    public boolean isWatching(String clusterId, String namespace) {
        return activeWatches.containsKey(clusterId + ":" + namespace);
    }

    public int activeWatchCount() {
        return activeWatches.size();
    }

    @PreDestroy
    void shutdown() {
        activeWatches.values().forEach(watches -> watches.forEach(Watch::close));
        activeWatches.clear();
    }

    <T extends HasMetadata> Watcher<T> driftWatcher(String clusterId, String resourceSlug) {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, T resource) {
                if (action == Action.MODIFIED || action == Action.DELETED) {
                    String     name   = resource.getMetadata().getName();
                    NodeId     nodeId = NodeId.of(clusterId + ":" + name + ":" + resourceSlug);
                    NodeStatus status = action == Action.DELETED ? NodeStatus.ABSENT : NodeStatus.DRIFTED;
                    String     detail = resourceSlug + " " + action.name().toLowerCase();
                    eventSource.emit(new StateEvent(nodeId, status, detail));
                    LOG.fine(() -> "Watch: " + action + " " + resourceSlug + "/" + name + " → " + status);
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    LOG.warning("Watch disconnected: " + clusterId + "/" + resourceSlug
                                + " — " + cause.getMessage() + ". Periodic resync (5 min) provides fallback coverage.");
                }
            }
        };
    }
}
