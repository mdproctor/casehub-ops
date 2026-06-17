package io.casehub.ops.api.deployment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseTypeNodeSpec(
        String namespace,
        String name,
        String version,
        String title,
        String summary,
        String definitionFile,
        Map<String, Object> definitionPayload
) implements DeploymentNodeSpec {

    public CaseTypeNodeSpec {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        definitionPayload = definitionPayload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(definitionPayload))
                : null;
    }

    @Override
    public String nodeId() {
        return namespace + ":" + name + ":" + version;
    }

    @Override
    public String nodeType() {
        return "case_type";
    }
}
