package io.casehub.ops.api.infra.state;

public record DriftedField(String field, String expected, String actual) {
}
