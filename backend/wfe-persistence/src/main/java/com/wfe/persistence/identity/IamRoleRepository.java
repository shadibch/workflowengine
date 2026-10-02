package com.wfe.persistence.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Role lookups used by the assignment resolver and the designer's picker. */
public interface IamRoleRepository extends JpaRepository<IamRoleEntity, Long> {

    Optional<IamRoleEntity> findByTenantIdAndCodeAndDeletedAtIsNull(String tenantId, String code);

    List<IamRoleEntity> findByTenantIdAndDeletedAtIsNullOrderByCodeAsc(String tenantId);

    /** Assignable roles only: the set a user task may name as a candidate group. */
    List<IamRoleEntity> findByTenantIdAndAssignableTrueAndDeletedAtIsNullOrderByCodeAsc(String tenantId);

    /**
     * Distinct role codes from a candidate list, for the publish-time validator to
     * reject a user task naming a role that does not exist or is not assignable.
     */
    @org.springframework.data.jpa.repository.Query("""
            select r.code
              from IamRoleEntity r
             where r.tenantId = :tenantId
               and r.code in :codes
               and r.deletedAt is null
               and r.assignable = true
            """)
    Set<String> findExistingAssignableCodes(@org.springframework.data.repository.query.Param("tenantId") String tenantId,
                                            @org.springframework.data.repository.query.Param("codes") List<String> codes);
}
