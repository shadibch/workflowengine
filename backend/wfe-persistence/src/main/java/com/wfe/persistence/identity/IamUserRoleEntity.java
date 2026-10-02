package com.wfe.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A role grant, with an optional validity window.
 *
 * <p>The window is what lets a temporary or acting role exist as data rather than
 * as a code path: an out-of-office cover grant simply has {@code valid_to} set,
 * and every authorization query filters on it, so nothing has to be revoked
 * afterwards or can be left behind by a forgotten cleanup job.
 */
@Entity
@Table(name = "iam_user_role")
public class IamUserRoleEntity {

    @Embeddable
    public record Id(Long userId, Long roleId) implements Serializable {

        public Id {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(roleId, "roleId");
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Null means the grant does not expire. */
    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IamUserRoleEntity() {
        // for JPA
    }

    public Id getId() {
        return id;
    }

    public void setId(Id id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public Long getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(Long grantedBy) {
        this.grantedBy = grantedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
