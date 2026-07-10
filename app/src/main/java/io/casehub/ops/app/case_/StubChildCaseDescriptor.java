package io.casehub.ops.app.case_;

import java.util.Map;

import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;

public final class StubChildCaseDescriptor {

    private StubChildCaseDescriptor() {}

    public static CaseDefinition build(String namespace, String name, String version) {
        String capabilityName = name + "-stub";
        return CaseDefinition.builder()
                .namespace(namespace)
                .name(name)
                .version(version)
                .title(name + " (stub)")
                .capabilities(Capability.of(capabilityName, "any", "any"))
                .workers(Worker.builder()
                        .name(name + "-stub-worker")
                        .capabilityName(capabilityName)
                        .function(new WorkerFunction.Sync<>(Map.class,
                                input -> WorkerResult.of(Map.of("status", "stub"))))
                        .build())
                .build();
    }
}
