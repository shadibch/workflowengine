package com.wfe.persistence.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data access for authorization decisions.
 *
 * <p>Every query here filters on {@code valid_from <= now < valid_to}, because
 * the answer to "does this user hold this role right now" is the only question
 * that matters when a task is claimed. Caching that answer beyond a request
 * would resurrect revoked access, which is the failure mode the hybrid identity
 * model exists to prevent.
 */
public interface IamUserRepository extends JpaRepository<IamUserEntity, Long> {

    /** Resolves the OIDC {@code sub} claim. The primary lookup on every request. */
    Optional<IamUserEntity> findByTenantIdAndExternalIdAndDeletedAtIsNull(String tenantId, String externalId);

    /**
     * Fallback for a token whose subject does not match any row, or for a design
     * that names a user by username before that user has ever logged in.
     */
    Optional<IamUserEntity> findByTenantIdAndUsernameIgnoreCaseAndDeletedAtIsNull(String tenantId, String username);

    /**
     * Role codes currently held by a user.
     *
     * <p>The validity window is evaluated in SQL rather than in Java so the
     * answer is correct regardless of the JVM's clock skew between nodes.
     *
     * <p>Non-assignable roles (ROLE_ADMIN) are included: {@code assignable=false}
     * means a user task may not target the role as a candidate group, not that
     * the user does not hold it. That constraint is applied where it belongs, by
     * {@link #findActiveUserIdsInRoles}, which is the only query that expands
     * candidate groups.
     */
    @Query("""
            select r.code
              from IamUserRoleEntity ur
              join IamRoleEntity r on r.id = ur.id.roleId
             where ur.id.userId = :userId
               and ur.tenantId = :tenantId
               and r.deletedAt is null
               and ur.validFrom <= :now
               and (ur.validTo is null or ur.validTo > :now)
            """)
    Set<String> findCurrentRoleCodes(@Param("userId") Long userId,
                                     @Param("tenantId") String tenantId,
                                     @Param("now") Instant now);

    /**
     * Permission codes granted through the user's current roles. Used to build the
     * authorities checked by
     * {@code @PreAuthorize("hasAuthority('permission:task:claim')")}, so what this
     * query returns is exactly what an authorization decision compares against.
     */
    @Query("""
            select distinct p.code
              from IamUserRoleEntity ur
              join IamRoleEntity r on r.id = ur.id.roleId
              join IamRolePermissionEntity rp on rp.id.roleId = r.id
              join IamPermissionEntity p on p.id = rp.id.permissionId
             where ur.id.userId = :userId
               and ur.tenantId = :tenantId
               and r.deletedAt is null
               and ur.validFrom <= :now
               and (ur.validTo is null or ur.validTo > :now)
            """)
    Set<String> findCurrentPermissionCodes(@Param("userId") Long userId,
                                           @Param("tenantId") String tenantId,
                                           @Param("now") Instant now);

    /**
     * Expands candidate-group role codes to the users that currently hold them.
     *
     * <p>Called when a user task is created. The result is snapshotted onto the
     * task; {@link #isCurrentMemberOfAny} re-validates it at claim time.
     */
    @Query("""
            select distinct u.id
              from IamUserEntity u
              join IamUserRoleEntity ur on ur.id.userId = u.id
              join IamRoleEntity r on r.id = ur.id.roleId
             where u.tenantId = :tenantId
               and u.deletedAt is null
               and u.status = com.wfe.persistence.identity.IamUserEntity.Status.ACTIVE
               and r.code in :roleCodes
               and r.deletedAt is null
               and r.assignable = true
               and ur.validFrom <= :now
               and (ur.validTo is null or ur.validTo > :now)
            """)
    Set<Long> findActiveUserIdsInRoles(@Param("tenantId") String tenantId,
                                       @Param("roleCodes") List<String> roleCodes,
                                       @Param("now") Instant now);

    /**
     * Whether a user still holds any of the candidate roles of a task, evaluated
     * at claim time. A false result blocks the claim, so revoking a role stops
     * access immediately even for a task created while the grant existed.
     */
    @Query("""
            select case when count(r) > 0 then true else false end
              from IamUserRoleEntity ur
              join IamRoleEntity r on r.id = ur.id.roleId
             where ur.id.userId = :userId
               and ur.tenantId = :tenantId
               and r.code in :roleCodes
               and r.deletedAt is null
               and ur.validFrom <= :now
               and (ur.validTo is null or ur.validTo > :now)
            """)
    boolean isCurrentMemberOfAny(@Param("userId") Long userId,
                                 @Param("tenantId") String tenantId,
                                 @Param("roleCodes") List<String> roleCodes,
                                 @Param("now") Instant now);

    /**
     * Marks the account as seen. Written on every login so the admin UI can show
     * accounts provisioned but never used.
     */
    @Query("update IamUserEntity u set u.lastLoginAt = :now, u.updatedAt = :now where u.id = :id")
    void touchLogin(@Param("id") Long id, @Param("now") Instant now);
}