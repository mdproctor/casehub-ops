package io.casehub.ops.api.infra.plan;

public record PlannedChange(ChangeAction action, String resourceAddress, String fieldSummary) {
}
