package io.casehub.ops.app.rest;

import io.casehub.ops.api.approval.RiskClassification;

import java.time.Instant;
import java.util.UUID;

public record ApprovalView(UUID workItemId, String nodeId, String action,
                           RiskClassification risk, String summary, String cluster,
                           String namespace, String status, String assigneeId,
                           Instant createdAt) {}
