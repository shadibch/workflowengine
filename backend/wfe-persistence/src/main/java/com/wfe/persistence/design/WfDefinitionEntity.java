package com.wfe.persistence.design;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A workflow definition: the stable business identity a designer authors under.
 *
 * <p>The mutable half of the model lives here — name, description, category, the
 * latest published version pointer and the draft lock. The design itself lives in
 * {@link WfVersionEntity}, one mutable draft plus any number of frozen published
 * versions, so a published version can never be edited in place.
 *
 * <p>{@code draftRevision} is the optimistic lock two designers hit
 * simultaneously. It is managed explicitly as a counter rather than by a JPA
 * {@code @Version} because the value is also a token the client holds: the SPA
 * sends the revision it based its edit on, and the save is a single conditional
 * {@code UPDATE ... WHERE draft_revision = ?}. The second designer is rejected with
 * the revision that won, and rebases instead of silently overwriting.
 */
@Entity
@Table(name = "wf_definition")
public class WfDefinitionEntity {

    public enum Status {
        /** Authored but never published. */
        DRAFT,
        /** Has a published version and may start instances. */
        ACTIVE,
        /** Temporarily barred from starting new instances; history stays readable. */
        SUSPENDED,
        /** Retired. Kept for the instances that already reference it. */
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /**
     * Business key, unique per tenant: {@code invoice-approval}. Stable across
     * versions and safe to put in URLs, API calls and audit records, which is why
     * it is separate from the display name and never reused after archiving.
     */
    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    /** Free-form grouping for the definition picker, e.g. {@code Finance}. */
    @Column(name = "category", length = 128)
    private String category;

    /**
     * Version started when a caller omits one. Null until the first publish, which
     * is what makes "no published version" answerable without a second query.
     */
    @Column(name = "latest_published_version_id")
    private Long latestPublishedVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** Managed explicitly; see the class javadoc. Bumped by the service on every draft write. */
    @Column(name = "draft_revision", nullable = false)
    private Long draftRevision;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft delete. Archiving is the supported retirement path; this column exists
     * for the cases where the row must disappear from pickers entirely (a mistaken
     * creation, a tenant cleanup) while published versions it references survive.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected WfDefinitionEntity() {
        // for JPA
    }

    public WfDefinitionEntity(String tenantId, String key, String name, Long createdBy, Instant now) {
        this.tenantId = tenantId;
        this.key = key;
        this.name = name;
        this.status = Status.DRAFT;
        this.draftRevision = 0L;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Long getLatestPublishedVersionId() {
        return latestPublishedVersionId;
    }

    public void setLatestPublishedVersionId(Long latestPublishedVersionId) {
        this.latestPublishedVersionId = latestPublishedVersionId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getDraftRevision() {
        return draftRevision;
    }

    /** Advances the draft revision. Called while the row is locked or after a successful CAS. */
    public void advanceDraftRevision() {
        this.draftRevision = (this.draftRevision == null ? 0L : this.draftRevision) + 1L;
    }

    public void touch(Long updatedBy, Instant now) {
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    /**
     * Retires the definition: no longer offered in pickers, still readable by key.
     *
     * <p>Both signals are set because they answer different questions. Status is
     * what a user sees ({@code ARCHIVED}); {@code deletedAt} is what keeps the row
     * out of every default query. A definition that is only archived still appears
     * in {@code search}, which is what allows its history to be found by name.
     */
    public void retire(Instant now) {
        this.status = Status.ARCHIVED;
        this.deletedAt = now;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
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

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
