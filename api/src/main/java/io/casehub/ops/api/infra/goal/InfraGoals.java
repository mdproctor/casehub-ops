package io.casehub.ops.api.infra.goal;

import java.util.List;

public record InfraGoals(
        String defaultBackend,
        List<ResourceDeclaration> resources,
        List<ImportDeclaration> imports) {

    public InfraGoals {
        // defaultBackend nullable
        resources = resources != null ? List.copyOf(resources) : List.of();
        imports = imports != null ? List.copyOf(imports) : List.of();
    }
}
