package com.wfe.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * A platform user, as authorised by the database.
 *
 * <p>Not a credentials record. The account lives in Keycloak and the
 * {@code external_id} column holds its OIDC subject; everything this entity
 * authorises — roles, locale, timezone, status — is local, so a change takes
 * effect on the next request instead of at token expiry.
 */
@Entity
@Table(name = "iam_user")
public class IamUserEntity {

    /** {@code ACTIVE}, {@code SUSPENDED} or {@code DISABLED}. */
    public enum Status {
        ACTIVE,
        SUSPENDED,
        DISABLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /**
     * OIDC {@code sub} claim. Null until the user first authenticates, which
     * allows provisioning ahead of the first login. Unique per tenant when set —
     * the partial index {@code ux_iam_user_external_id} permits many nulls.
     */
    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    /** IANA zone id, e.g. {@code Asia/Dubai}. Drives timer and SLA rendering. */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false)
    private Map<String, Object> attributes;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected IamUserEntity() {
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

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * Whether this account may act. A suspended or disabled account keeps its
     * roles on record but cannot authenticate into an action.
     */
    public boolean isActive() {
        return status == Status.ACTIVE && deletedAt == null;
    }
}
