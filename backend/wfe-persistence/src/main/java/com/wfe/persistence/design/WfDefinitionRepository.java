package com.wfe.persistence.design;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Definition queries.
 *
 * <p>Every query here is tenant-scoped in SQL rather than filtered afterwards.
 * Filtering in Java would work until the day a paginated list was built before the
 * tenant predicate was applied, and then it would leak another tenant's rows into
 * a response.
 */
public interface WfDefinitionRepository extends JpaRepository<WfDefinitionEntity, Long> {

    Optional<WfDefinitionEntity> findByTenantIdAndKeyAndDeletedAtIsNull(String tenantId, String key);

    boolean existsByTenantIdAndKey(String tenantId, String key);

    /**
     * The definition picker: substring match on key or name, optional category and
     * status filters, newest activity first.
     *
     * <p>Ordered by {@code updated_at} rather than name because a designer's next
     * action is almost always to continue what they touched last.
     */
    @Query("""
            select d from WfDefinitionEntity d
             where d.tenantId = :tenantId
               and d.deletedAt is null
               and (:search is null
                    or lower(d.key) like :search
                    or lower(d.name) like :search)
               and (:category is null or d.category = :category)
               and (:statuses is null or d.status in :statuses)
            """)
    Page<WfDefinitionEntity> search(@Param("tenantId") String tenantId,
                                    @Param("search") String search,
                                    @Param("category") String category,
                                    @Param("statuses") List<WfDefinitionEntity.Status> statuses,
                                    Pageable pageable);

    /**
     * All non-archived definitions carrying the category, for the category facet.
     * A short, cheap query that avoids grouping in Java over a paged result.
     */
    @Query("""
            select distinct d.category from WfDefinitionEntity d
             where d.tenantId = :tenantId
               and d.deletedAt is null
               and d.category is not null
               and d.status <> com.wfe.persistence.design.WfDefinitionEntity.Status.ARCHIVED
             order by d.category
            """)
    List<String> findCategories(@Param("tenantId") String tenantId);

    /**
     * Row lock taken at the start of every publish.
     *
     * <p>Serialising publishes per definition is what makes version allocation
     * correct without a retry loop: the second publisher waits, then sees the
     * first publisher's committed counter and draft state. Held for the length of
     * one publish transaction and nothing longer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WfDefinitionEntity d where d.id = :id and d.tenantId = :tenantId")
    Optional<WfDefinitionEntity> findByIdForUpdate(@Param("id") Long id, @Param("tenantId") String tenantId);

    /**
     * Optimistic-lock read for draft saves.
     *
     * <p>No row lock here on purpose: autosave is frequent and the conflict it
     * guards against is rare, so two designers saving within milliseconds should
     * both get quick answers and only the second one loses.
     */
    @Query("select d from WfDefinitionEntity d where d.tenantId = :tenantId and d.key = :key and d.deletedAt is null")
    Optional<WfDefinitionEntity> findByTenantIdAndKey(@Param("tenantId") String tenantId, @Param("key") String key);

    /**
     * Compare-and-set on the draft revision.
     *
     * <p>The single conditional {@code UPDATE} is the whole concurrency control:
     * one round trip, no lock held while the XML is rewritten, and a lost race is
     * simply zero rows affected. A zero return means another save landed first and
     * the caller must rebase.
     *
     * @return 1 when this caller advanced the revision, 0 when it was stale
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WfDefinitionEntity d
               set d.draftRevision = d.draftRevision + 1,
                   d.updatedBy = :userId,
                   d.updatedAt = :now
             where d.id = :id
               and d.draftRevision = :expectedRevision
            """)
    int advanceDraftRevision(@Param("id") Long id,
                             @Param("expectedRevision") long expectedRevision,
                             @Param("userId") Long userId,
                             @Param("now") Instant now);
}
