package com.wfe.persistence.design;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Version queries for a definition.
 *
 * <p>All reads go through {@code definitionId} plus the caller's tenant, never a
 * bare version id, so a guessed id from another tenant resolves to nothing rather
 * than to somebody else's design.
 */
public interface WfVersionRepository extends JpaRepository<WfVersionEntity, Long> {

    Optional<WfVersionEntity> findByTenantIdAndDefinitionIdAndVersionNoAndDeletedAtIsNull(
            String tenantId, Long definitionId, Integer versionNo);

    /** Newest first: the version list is a history, read top-down. */
    List<WfVersionEntity> findByTenantIdAndDefinitionIdAndDeletedAtIsNullOrderByVersionNoDesc(
            String tenantId, Long definitionId);

    /**
     * The single mutable draft. Non-empty result is guaranteed by the service
     * layer, which creates the draft when a definition is created and reopens one
     * on every publish.
     */
    Optional<WfVersionEntity> findByTenantIdAndDefinitionIdAndStatusAndDeletedAtIsNull(
            String tenantId, Long definitionId, WfVersionEntity.Status status);

    /** Published versions only, newest first: what a start request may choose from. */
    List<WfVersionEntity> findByTenantIdAndDefinitionIdAndStatusInAndDeletedAtIsNullOrderByVersionNoDesc(
            String tenantId, Long definitionId, List<WfVersionEntity.Status> statuses);

    /**
     * Whether this exact design has already been published, used to make
     * republishing an unchanged design a no-op.
     *
     * <p>Published rows only, matching the partial unique index
     * {@code ux_wf_version_checksum}. Drafts are excluded on purpose: the draft
     * being published is a row of its own with the same checksum, so including
     * drafts would return either that row or the published parent depending on
     * which one the planner reached first.
     */
    Optional<WfVersionEntity> findByTenantIdAndDefinitionIdAndChecksumAndStatusAndDeletedAtIsNull(
            String tenantId, Long definitionId, String checksum, WfVersionEntity.Status status);

    @Query("""
            select count(v) from WfVersionEntity v
             where v.tenantId = :tenantId
               and v.definitionId = :definitionId
               and v.status = com.wfe.persistence.design.WfVersionEntity.Status.PUBLISHED
               and v.deletedAt is null
            """)
    long countPublished(@Param("tenantId") String tenantId, @Param("definitionId") Long definitionId);

    /**
     * Resolves several version ids to their numbers in one round trip.
     *
     * <p>Exists so the definition list can show "latest published: v4" for twenty
     * rows without twenty extra queries. Returns {@code (id, versionNo)} pairs.
     */
    @Query("select v.id, v.versionNo from WfVersionEntity v where v.id in :ids")
    List<Object[]> resolveVersionNumbers(@Param("ids") List<Long> ids);
}
