package io.casehub.ops.app.service;

import java.util.UUID;

@FunctionalInterface
public interface CaseSignaler {
    void signal(UUID caseId, String path, Object value);
}
