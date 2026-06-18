package io.casehub.ops.compliance;

import io.casehub.ops.api.compliance.ComplianceControlSpec;
import io.casehub.ops.api.compliance.FrameworkMapping;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ComplianceFrameworkRegistry {

    private final ConcurrentHashMap<String, ComplianceControlSpec> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> frameworkToControls = new ConcurrentHashMap<>();

    public void register(ComplianceControlSpec spec) {
        controls.put(spec.controlId(), spec);
        for (var fm : spec.frameworks()) {
            frameworkToControls
                    .computeIfAbsent(fm.framework(), k -> ConcurrentHashMap.newKeySet())
                    .add(spec.controlId());
        }
    }

    public void deregister(String controlId) {
        ComplianceControlSpec removed = controls.remove(controlId);
        if (removed != null) {
            for (var fm : removed.frameworks()) {
                Set<String> ids = frameworkToControls.get(fm.framework());
                if (ids != null) {
                    ids.remove(controlId);
                    if (ids.isEmpty()) {
                        frameworkToControls.remove(fm.framework());
                    }
                }
            }
        }
    }

    public List<ComplianceControlSpec> controlsForFramework(String framework) {
        Set<String> ids = frameworkToControls.get(framework);
        if (ids == null) return List.of();
        return ids.stream().map(controls::get).filter(Objects::nonNull).toList();
    }

    public List<FrameworkMapping> frameworksForControl(String controlId) {
        ComplianceControlSpec spec = controls.get(controlId);
        return spec != null ? spec.frameworks() : List.of();
    }

    public Set<String> registeredFrameworks() {
        return Set.copyOf(frameworkToControls.keySet());
    }

    public Optional<ComplianceControlSpec> findControl(String controlId) {
        return Optional.ofNullable(controls.get(controlId));
    }
}
