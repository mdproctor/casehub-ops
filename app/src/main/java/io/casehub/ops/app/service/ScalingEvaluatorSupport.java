package io.casehub.ops.app.service;

import io.casehub.ops.app.entity.ApplicationEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;

@ApplicationScoped
public class ScalingEvaluatorSupport {

    @Transactional
    public String loadServicesJson(UUID applicationId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        return app != null ? app.servicesJson : null;
    }
}
