package io.casehub.ops.app.entity;

import io.casehub.ops.app.model.DeploymentOutcome;
import io.casehub.ops.app.model.DeploymentTrigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class DeploymentRecordEntityTest {

    @Test
    @Transactional
    void persistsAndFindsByApplicationId() {
        var appId = UUID.randomUUID();

        // Create application first (FK constraint)
        var app = new ApplicationEntity();
        app.id = appId;
        app.name = "test";
        app.tenancyId = "default";
        app.servicesJson = "[]";
        app.persist();

        var record = new DeploymentRecordEntity();
        record.applicationId = appId;
        record.topologyJson = "{\"gateway\": \"img:1.0\"}";
        record.trigger = DeploymentTrigger.INITIAL;
        record.outcome = DeploymentOutcome.SUCCESS;
        record.persist();

        assertThat(record.id).isNotNull();
        var results = DeploymentRecordEntity.findByApplicationId(appId);
        assertThat(results).hasSize(1);
    }
}
