package io.casehub.ops.api.deployment;

public record CaseTypeNodeSpec(
        String namespace,
        String name,
        String version,
        String title,
        String summary
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
