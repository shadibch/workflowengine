package com.wfe.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single API capability, e.g. {@code task:claim}.
 *
 * <p>A flat permission table rather than a policy engine. Workflow authorization
 * is almost entirely "which queues may this user act in", and expressing that as
 * role membership plus a handful of capabilities keeps the rules reviewable by
 * an administrator without a policy language.
 */
@Entity
@Table(name = "iam_permission")
public class IamPermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "code", nullable = false, length = 128)
    private String code;

    @Column(name = "description", length = 512)
    private String description;

    protected IamPermissionEntity() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
