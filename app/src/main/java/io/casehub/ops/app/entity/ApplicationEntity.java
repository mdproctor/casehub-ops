package io.casehub.ops.app.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.casehub.ops.app.model.ApplicationStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "application")
public class ApplicationEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(nullable = false)
    public String name;

    public String description;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    @Column(name = "services_json", nullable = false, columnDefinition = "TEXT")
    public String servicesJson;

    @Column(name = "compliance_policies_json", columnDefinition = "TEXT")
    public String compliancePoliciesJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public ApplicationStatus status;

    @Column(name = "engine_case_id")
    public UUID engineCaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onPersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
        if (status == null) status = ApplicationStatus.DRAFT;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static List<ApplicationEntity> findByTenancyId(String tenancyId) {
        return list("tenancyId", tenancyId);
    }

    public static List<ApplicationEntity> findActiveByTenancyId(String tenancyId) {
        return list("tenancyId = ?1 and status not in (?2)", tenancyId,
                List.of(ApplicationStatus.DRAFT, ApplicationStatus.DECOMMISSIONED));
    }
}
