package io.casehub.ops.api.infra.goal;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record ResourceDeclaration(
        String id,
        String type,
        String backend,
        JsonNode config,
        List<String> dependsOn) {

    public ResourceDeclaration {
        // backend nullable — inherits from InfraGoals.defaultBackend
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
