package io.casehub.ops.app.authorization;

import io.casehub.ops.api.approval.ApprovalAuthorizer;
import io.casehub.ops.api.approval.RiskClassification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ConfigDrivenApprovalAuthorizer implements ApprovalAuthorizer {

    private final Map<RiskClassification, Set<String>> requiredRoles;

    @Inject
    public ConfigDrivenApprovalAuthorizer(
            @ConfigProperty(name = "casehub.ops.approval.roles.critical", defaultValue = "")
            List<String> criticalRoles,
            @ConfigProperty(name = "casehub.ops.approval.roles.high", defaultValue = "")
            List<String> highRoles) {
        this.requiredRoles = Map.of(
                RiskClassification.CRITICAL, toSet(criticalRoles),
                RiskClassification.HIGH, toSet(highRoles));
    }

    public ConfigDrivenApprovalAuthorizer(Set<String> criticalRoles, Set<String> highRoles) {
        this.requiredRoles = Map.of(
                RiskClassification.CRITICAL, Set.copyOf(criticalRoles),
                RiskClassification.HIGH, Set.copyOf(highRoles));
    }

    @Override
    public AuthorizationResult authorize(RiskClassification risk, String actorId, Set<String> actorRoles) {
        Set<String> required = requiredRoles.getOrDefault(risk, Set.of());
        if (required.isEmpty()) {
            return new AuthorizationResult.Authorized();
        }

        for (String role : required) {
            if (actorRoles.contains(role)) {
                return new AuthorizationResult.Authorized();
            }
        }

        return new AuthorizationResult.Denied(
                "Actor '" + actorId + "' lacks required role for " + risk
                + " operations. Required one of: " + required);
    }

    private static Set<String> toSet(List<String> list) {
        if (list.isEmpty() || (list.size() == 1 && list.getFirst().isEmpty())) {
            return Set.of();
        }
        return Set.copyOf(list);
    }
}
