package io.casehub.ops.api.infra.spi;

import io.casehub.ops.api.infra.state.ResourceState;

public sealed interface BackendProvisionResult
        permits BackendProvisionResult.Provisioned, BackendProvisionResult.Failed {

    record Provisioned(ResourceState state) implements BackendProvisionResult {}

    record Failed(String reason, boolean retryable) implements BackendProvisionResult {}
}
