package io.casehub.ops.app.service;

import io.casehub.ops.app.case_.ScalingPolicy;

import java.util.UUID;

public record ScalingRequestedEvent(
        UUID appCaseId,
        String applicationId,
        String tenancyId,
        String serviceId,
        int targetReplicas,
        int currentReplicas,
        String reason,
        ScalingPolicy policy) {}
