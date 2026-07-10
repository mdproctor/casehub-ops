package io.casehub.ops.app.model;

import java.util.List;

public record NodeDrift(String nodeId, List<FieldDrift> fields) {}
