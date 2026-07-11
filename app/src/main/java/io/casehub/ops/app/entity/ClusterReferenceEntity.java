package io.casehub.ops.app.entity;

import io.casehub.ops.app.model.ClusterStatus;
import io.casehub.ops.app.model.ClusterType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "cluster_reference")
public class ClusterReferenceEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(nullable = false)
    public String name;

    @Column(name = "api_url", nullable = false)
    public String apiUrl;

    @Column(nullable = false)
    public String namespace;

    @Column(name = "credential_ref")
    public String credentialRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "cluster_type", nullable = false, length = 30)
    public ClusterType clusterType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public ClusterStatus status;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;
    @Column(name = "trust_certs", nullable = false)
    public boolean trustCerts = true;


    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onPersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = ClusterStatus.UNKNOWN;
    }

    public static List<ClusterReferenceEntity> findByTenancyId(String tenancyId) {
        return list("tenancyId", tenancyId);
    }
}
