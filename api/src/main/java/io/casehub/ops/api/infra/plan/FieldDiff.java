package io.casehub.ops.api.infra.plan;

public record FieldDiff(String field, String before, String after) {
}
