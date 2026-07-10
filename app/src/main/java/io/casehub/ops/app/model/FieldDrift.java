package io.casehub.ops.app.model;

public record FieldDrift(String fieldName, String expectedValue, String actualValue) {}
