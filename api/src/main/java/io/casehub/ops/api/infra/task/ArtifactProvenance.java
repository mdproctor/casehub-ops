package io.casehub.ops.api.infra.task;

import java.time.Instant;

public sealed interface ArtifactProvenance
        permits ArtifactProvenance.HandWritten, ArtifactProvenance.LlmGenerated, ArtifactProvenance.CachedReuse {

    record LlmGenerated(String model, Instant generatedAt, String specHash) implements ArtifactProvenance {}

    record CachedReuse(String originalHash, Instant cachedAt) implements ArtifactProvenance {}

    record HandWritten(String author) implements ArtifactProvenance {}
}
