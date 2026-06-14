package io.casehub.ops.api.infra.spi;

import io.casehub.desiredstate.api.NodeId;

public sealed interface BackendDeprovisionResult
        permits BackendDeprovisionResult.Deprovisioned, BackendDeprovisionResult.Failed {

    record Deprovisioned(NodeId nodeId) implements BackendDeprovisionResult {}

    record Failed(String reason, boolean retryable) implements BackendDeprovisionResult {}
}
