package io.casehub.ops.app.rest.dto;

import io.casehub.ops.app.model.ClusterType;

public record RegisterClusterRequest(
        String name,
        String apiUrl,
        String namespace,
        String credentialRef,
        ClusterType clusterType) {}
