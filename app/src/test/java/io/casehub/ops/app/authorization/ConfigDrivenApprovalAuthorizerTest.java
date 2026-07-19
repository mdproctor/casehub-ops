package io.casehub.ops.app.authorization;

import io.casehub.ops.api.approval.ApprovalAuthorizer.AuthorizationResult;
import io.casehub.ops.api.approval.RiskClassification;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigDrivenApprovalAuthorizerTest {

    @Test
    void noRolesConfigured_alwaysAuthorizes() {
        var authorizer = new ConfigDrivenApprovalAuthorizer(Set.of(), Set.of());

        assertThat(authorizer.authorize(RiskClassification.CRITICAL, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Authorized.class);
        assertThat(authorizer.authorize(RiskClassification.HIGH, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Authorized.class);
        assertThat(authorizer.authorize(RiskClassification.LOW, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Authorized.class);
    }

    @Test
    void criticalRequiresRole_actorHasRole_authorized() {
        var authorizer = new ConfigDrivenApprovalAuthorizer(Set.of("ops-admin"), Set.of());

        var result = authorizer.authorize(
                RiskClassification.CRITICAL, "actor-1", Set.of("ops-admin", "ops-operator"));
        assertThat(result).isInstanceOf(AuthorizationResult.Authorized.class);
    }

    @Test
    void criticalRequiresRole_actorLacksRole_denied() {
        var authorizer = new ConfigDrivenApprovalAuthorizer(Set.of("ops-admin"), Set.of());

        var result = authorizer.authorize(
                RiskClassification.CRITICAL, "actor-1", Set.of("ops-operator"));
        assertThat(result).isInstanceOf(AuthorizationResult.Denied.class);
        var denied = (AuthorizationResult.Denied) result;
        assertThat(denied.reason()).contains("actor-1").contains("ops-admin");
    }

    @Test
    void highRequiresRole_oneOfMultipleRolesSuffices() {
        var authorizer = new ConfigDrivenApprovalAuthorizer(
                Set.of(), Set.of("ops-senior", "ops-lead"));

        var result = authorizer.authorize(
                RiskClassification.HIGH, "actor-1", Set.of("ops-lead"));
        assertThat(result).isInstanceOf(AuthorizationResult.Authorized.class);
    }

    @Test
    void lowAndMedium_neverGated() {
        var authorizer = new ConfigDrivenApprovalAuthorizer(
                Set.of("ops-admin"), Set.of("ops-senior"));

        assertThat(authorizer.authorize(RiskClassification.LOW, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Authorized.class);
        assertThat(authorizer.authorize(RiskClassification.MEDIUM, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Authorized.class);
    }

    @Test
    void emptyActorRoles_deniedWhenRolesRequired() {
        var authorizer = new ConfigDrivenApprovalAuthorizer(
                Set.of("ops-admin"), Set.of("ops-senior"));

        assertThat(authorizer.authorize(RiskClassification.CRITICAL, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Denied.class);
        assertThat(authorizer.authorize(RiskClassification.HIGH, "actor-1", Set.of()))
                .isInstanceOf(AuthorizationResult.Denied.class);
    }
}
