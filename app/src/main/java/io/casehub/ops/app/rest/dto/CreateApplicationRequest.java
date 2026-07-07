package io.casehub.ops.app.rest.dto;

public record CreateApplicationRequest(String name, String description, String servicesJson) {}
