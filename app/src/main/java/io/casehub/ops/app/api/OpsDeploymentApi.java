package io.casehub.ops.app.api;

import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.entity.DeploymentRecordEntity;
import io.casehub.ops.app.model.DeploymentOutcome;
import io.casehub.ops.app.rest.dto.DeployRequest;
import io.casehub.ops.app.service.ApplicationLifecycleService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@McpDomain(value = "ops/deployments", basePath = "/api/ops/deployments")
@ApplicationScoped
public class OpsDeploymentApi {

    @Inject ApplicationLifecycleService lifecycleService;
    @Inject EntityManager em;

    @PlatformMutation("Deploy an application")
    @RestPath("/{applicationId}/deploy")
    public void deploy(@PathParam UUID applicationId, DeployRequest request) {
        var app = em.find(ApplicationEntity.class, applicationId);
        if (app == null) { throw new IllegalArgumentException("Application not found: " + applicationId); }
        lifecycleService.deploy(applicationId, app.tenancyId);
    }

    @PlatformQuery("List deployments for an application")
    @RestPath("/{applicationId}")
    public List<DeploymentRecordEntity> listDeployments(@PathParam UUID applicationId) {
        return em.createNamedQuery("DeploymentRecordEntity.findByApplicationId", DeploymentRecordEntity.class)
                .setParameter("applicationId", applicationId).getResultList();
    }

    @PlatformQuery("Get current deployment")
    @RestPath("/{applicationId}/current")
    public DeploymentRecordEntity getCurrentDeployment(@PathParam UUID applicationId) {
        var records = em.createNamedQuery("DeploymentRecordEntity.findByApplicationId", DeploymentRecordEntity.class)
                .setParameter("applicationId", applicationId).getResultList();
        return records.stream()
                .max(Comparator.comparing(r -> r.createdAt))
                .orElse(null);
    }

    @PlatformMutation("Rollback an application to previous deployment")
    @RestPath("/{applicationId}/rollback")
    public void rollback(@PathParam UUID applicationId) {
        var app = em.find(ApplicationEntity.class, applicationId);
        if (app == null) { throw new IllegalArgumentException("Application not found: " + applicationId); }

        var records = em.createNamedQuery("DeploymentRecordEntity.findByApplicationId", DeploymentRecordEntity.class)
                .setParameter("applicationId", applicationId).getResultList();
        var lastSuccess = records.stream()
                .filter(r -> r.outcome == DeploymentOutcome.SUCCESS)
                .max(Comparator.comparing(r -> r.createdAt))
                .orElseThrow(() -> new IllegalStateException("No successful deployment to rollback to"));

        lifecycleService.rollbackToDeployment(applicationId, lastSuccess.id, app.tenancyId);
    }
}
