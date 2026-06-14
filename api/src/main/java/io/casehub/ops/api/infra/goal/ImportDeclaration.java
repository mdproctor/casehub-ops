package io.casehub.ops.api.infra.goal;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record ImportDeclaration(
        String id,
        String type,
        JsonNode config,
        List<String> dependsOn) {

    public ImportDeclaration {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
    }
}
