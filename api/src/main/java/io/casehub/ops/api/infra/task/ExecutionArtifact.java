package io.casehub.ops.api.infra.task;

public record ExecutionArtifact(
        String contentHash,
        ArtifactType type,
        String content,
        ArtifactProvenance provenance) {
}
