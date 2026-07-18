package io.casehub.ops.app.rest.dto;

public record ScaleServiceRequest(int targetReplicas, String reason) {}
