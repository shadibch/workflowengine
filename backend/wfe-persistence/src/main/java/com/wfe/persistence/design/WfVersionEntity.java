package com.wfe.persistence.design;

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
import java.util.List;
import java.util.Map;

/**
 * One version of a definition's BPMN.
 *
 * <p>A definition holds exactly one {@link Status#DRAFT} row and any number of
 * frozen published ones. Publishing does not mutate the draft's XML in place: it
 * stamps the row as published and opens a new draft seeded from it, so an instance
 * that started against version 3 keeps the exact document it started with, byte
 * for byte, for the life of its history.
 *
 * <p>{@code graph} stays null until the compiler runs in a later phase; the column
 * exists now so publishing has somewhere to write and so no future migration has
 * to rewrite rows that are already being produced.
 */
@Entity
@Table(name = "wf_version")
public class WfVersionEntity {

    public enum Status {
        DRAFT,
        PUBLISHED,
        /** Superseded by a newer version but still startable by explicit request. */
        DEPRECATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    /** 1-based, monotonically increasing, allocated under the definition's row lock. */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /**
     * The designer's payload, stored verbatim: definitions, lanes, DI layout and
     * the {@code wfe:} extension attributes. Export serves these bytes unchanged,
     * so a round trip through this engine is lossless and other BPMN tools can
     * still read the file.
     *
     * <p>No {@code @Lob}: on PostgreSQL that maps to {@code oid} and turns every
     * read into a large-object fetch. Plain {@code String} against the column's
     * declared {@code text} type is the correct mapping and keeps the document in
     * the normal heap.
     */
    @Column(name = "bpmn_xml", nullable = false)
    private String bpmnXml;

    /** Compiled execution graph. Null while the draft is incomplete. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph")
    private Map<String, Object> graph;

    /**
     * SHA-256 of the canonicalised XML. The unique index on
     * {@code (definition_id, checksum)} is what turns "publish the same design
     * again" into a no-op instead of version noise.
     *
     * <p>Mapped as {@code CHAR(64)} to match the column: a digest is always exactly
     * 64 characters, so a fixed-width column states the invariant in the schema
     * instead of leaving it to a convention, and Hibernate's schema validation
     * catches a future mismatch here rather than at the first failed insert.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    /** Validator output as returned, so the designer can reopen an old version and see its findings. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation")
    private List<Map<String, Object>> validation;

    /** WSDL/OpenAPI fingerprints captured at publish time; see V2 design notes. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_refs", nullable = false)
    private List<String> schemaRefs = List.of();

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected WfVersionEntity() {
        // for JPA
    }

    public WfVersionEntity(String tenantId, Long definitionId, int versionNo, String bpmnXml,
                           String checksum, Long createdBy, Instant now) {
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.versionNo = versionNo;
        this.status = Status.DRAFT;
        this.bpmnXml = bpmnXml;
        this.checksum = checksum;
        this.schemaRefs = List.of();
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getBpmnXml() {
        return bpmnXml;
    }

    public void setBpmnXml(String bpmnXml) {
        this.bpmnXml = bpmnXml;
    }

    public Map<String, Object> getGraph() {
        return graph;
    }

    public void setGraph(Map<String, Object> graph) {
        this.graph = graph;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public List<Map<String, Object>> getValidation() {
        return validation;
    }

    public void setValidation(List<Map<String, Object>> validation) {
        this.validation = validation;
    }

    public List<String> getSchemaRefs() {
        return schemaRefs;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Freezes this row as an immutable published artefact. */
    public void markPublished(Long publishedBy, Instant now) {
        this.status = Status.PUBLISHED;
        this.publishedBy = publishedBy;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    /** Records a draft edit without touching status, so a save can never accidentally publish. */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    public boolean isPublished() {
        return status == Status.PUBLISHED;
    }
}
