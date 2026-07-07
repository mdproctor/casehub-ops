package io.casehub.ops.app.entity;

import io.casehub.ops.app.model.ApplicationStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class ApplicationEntityTest {

    @Test
    @Transactional
    void persistsAndFindsApplication() {
        var app = new ApplicationEntity();
        app.name = "online-store";
        app.description = "Demo application";
        app.tenancyId = "default";
        app.servicesJson = "[]";
        app.status = ApplicationStatus.DRAFT;
        app.persist();

        assertThat(app.id).isNotNull();
        assertThat(app.createdAt).isNotNull();

        var found = ApplicationEntity.findById(app.id);
        assertThat(found).isNotNull();
    }

    @Test
    @Transactional
    void findsByTenancyIdAndStatus() {
        var app = new ApplicationEntity();
        app.name = "test-app";
        app.tenancyId = "tenant-1";
        app.servicesJson = "[]";
        app.status = ApplicationStatus.RUNNING;
        app.persist();

        var results = ApplicationEntity.findByTenancyId("tenant-1");
        assertThat(results).isNotEmpty();
    }
}
