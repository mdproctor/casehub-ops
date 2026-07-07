package io.casehub.ops.app.k8s;

import java.util.Map;
import java.util.stream.Collectors;

import io.casehub.ops.api.infra.InfraNodeSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class K8sHandlerRegistry {

    private final Map<Class<? extends InfraNodeSpec>, K8sResourceHandler<?>> handlers;

    @Inject
    public K8sHandlerRegistry(@Any Instance<K8sResourceHandler<?>> discovered) {
        this.handlers = discovered.stream()
                .collect(Collectors.toMap(K8sResourceHandler::specType, h -> h));
    }

    K8sHandlerRegistry(java.util.List<K8sResourceHandler<?>> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(K8sResourceHandler::specType, h -> h));
    }

    @SuppressWarnings("unchecked")
    public <S extends InfraNodeSpec> K8sResourceHandler<S> handlerFor(Class<S> specType) {
        K8sResourceHandler<?> handler = handlers.get(specType);
        if (handler == null) {
            throw new IllegalArgumentException("No handler for spec type: " + specType.getSimpleName());
        }
        return (K8sResourceHandler<S>) handler;
    }
}
