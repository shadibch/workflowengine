package com.wfe.persistence.design;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-definition version counter.
 *
 * <p>A separate table rather than {@code max(version_no) + 1} on
 * {@code wf_version}: the allocation happens inside the definition's row lock, so
 * the counter row is already owned by the publishing transaction and needs no
 * second lock or a retry loop. The unique index on {@code (definition_id,
 * version_no)} remains the backstop if that discipline is ever broken.
 */
@Entity
@Table(name = "wf_version_counter")
public class WfVersionCounterEntity {

    @Id
    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    @Column(name = "last_version_no", nullable = false)
    private Integer lastVersionNo;

    protected WfVersionCounterEntity() {
        // for JPA
    }

    public WfVersionCounterEntity(Long definitionId) {
        this.definitionId = definitionId;
        this.lastVersionNo = 0;
    }

    public Long getDefinitionId() {
        return definitionId;
    }

    public Integer getLastVersionNo() {
        return lastVersionNo;
    }

    /** Reserves the next version number and returns it. */
    public int allocate() {
        this.lastVersionNo = this.lastVersionNo + 1;
        return this.lastVersionNo;
    }
}
