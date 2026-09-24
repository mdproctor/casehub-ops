package io.casehub.ops.app.api;

import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.rest.dto.CreateApplicationRequest;
import io.casehub.ops.app.service.ApplicationLifecycleService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "ops/applications", app = "ops", basePath = "/api/ops/applications", summary = "Application lifecycle — register, deploy, configure, retire")
@ApplicationScoped
public class OpsApplicationApi {

    @Inject ApplicationLifecycleService lifecycleService;
    @Inject EntityManager em;

    @PlatformMutation("Create an application")
    @RestPath("/")
    public ApplicationEntity createApplication(CreateApplicationRequest request) {
        return lifecycleService.createDraft(
                request.name(), request.description(), request.servicesJson(), null);
    }

    @PlatformQuery("List all applications")
    @RestPath("/")
    public List<ApplicationEntity> listApplications() {
        return em.createQuery("SELECT a FROM ApplicationEntity a", ApplicationEntity.class)
                .getResultList();
    }

    @PlatformQuery("Get an application by ID")
    @RestPath("/{id}")
    public ApplicationEntity getApplication(@PathParam UUID id) {
        return em.find(ApplicationEntity.class, id);
    }

    @PlatformMutation("Update an application")
    @RestPath("/{id}")
    public ApplicationEntity updateApplication(@PathParam UUID id, CreateApplicationRequest request) {
        var app = em.find(ApplicationEntity.class, id);
        if (app == null) { return null; }
        if (request.name() != null) app.name = request.name();
        if (request.description() != null) app.description = request.description();
        if (request.servicesJson() != null) app.servicesJson = request.servicesJson();
        return em.merge(app);
    }

    @PlatformMutation("Delete an application")
    @RestPath("/{id}/delete")
    public void deleteApplication(@PathParam UUID id) {
        var app = em.find(ApplicationEntity.class, id);
        if (app != null) {
            lifecycleService.decommission(id, app.tenancyId);
        }
    }
}
