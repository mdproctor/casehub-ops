package io.casehub.ops.api.approval;

import java.util.Set;

public interface ApprovalAuthorizer {
    AuthorizationResult authorize(RiskClassification risk, String actorId, Set<String> actorRoles);

    sealed interface AuthorizationResult {
        record Authorized() implements AuthorizationResult {}
        record Denied(String reason) implements AuthorizationResult {}
    }
}
