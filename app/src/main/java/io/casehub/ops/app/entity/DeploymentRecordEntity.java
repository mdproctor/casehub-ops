package io.casehub.ops.app.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.casehub.ops.app.model.DeploymentOutcome;
import io.casehub.ops.app.model.DeploymentTrigger;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "deployment_record")
public class DeploymentRecordEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "application_id", nullable = false)
    public UUID applicationId;

    @Column(name = "topology_json", nullable = false, columnDefinition = "TEXT")
    public String topologyJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public DeploymentTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public DeploymentOutcome outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onPersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public static List<DeploymentRecordEntity> findByApplicationId(UUID applicationId) {
        return list("applicationId", applicationId);
    }
}
