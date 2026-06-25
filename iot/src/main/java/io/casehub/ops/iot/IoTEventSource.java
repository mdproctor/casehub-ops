package io.casehub.ops.iot;

import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class IoTEventSource implements EventSource {

    private final BroadcastProcessor<StateEvent> processor;
    private final Multi<StateEvent> stream;
    private final ConcurrentHashMap<String, Set<String>> providerDevices;

    public IoTEventSource() {
        this.processor = BroadcastProcessor.create();
        this.stream = Multi.createFrom().publisher(processor);
        this.providerDevices = new ConcurrentHashMap<>();
    }

    @Override
    public Multi<StateEvent> stream() {
        return stream;
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        String deviceId = event.after().deviceId();
        String providerId = event.after().providerId();

        providerDevices
            .computeIfAbsent(providerId, k -> ConcurrentHashMap.newKeySet())
            .add(deviceId);

        if (event.before() == null) {
            emit(new StateEvent(NodeId.of(deviceId), NodeStatus.PRESENT,
                "device discovered"));
        } else if (!event.after().available() && event.before().available()) {
            emit(new StateEvent(NodeId.of(deviceId), NodeStatus.DRIFTED,
                "device offline"));
        } else if (!event.changedCapabilities().isEmpty()) {
            emit(new StateEvent(NodeId.of(deviceId + "-config"), NodeStatus.DRIFTED,
                "capabilities changed: " + event.changedCapabilities()));
        }
    }

    void onProviderStatus(@ObservesAsync ProviderStatusEvent event) {
        if (event.currentStatus() == ProviderStatus.DISCONNECTED) {
            Set<String> devices = providerDevices.get(event.providerId());
            if (devices != null) {
                for (String deviceId : devices) {
                    emit(new StateEvent(NodeId.of(deviceId), NodeStatus.UNKNOWN,
                        "provider disconnected: " + event.providerId()));
                }
            }
        }
    }

    private void emit(StateEvent event) {
        processor.onNext(event);
    }
}
