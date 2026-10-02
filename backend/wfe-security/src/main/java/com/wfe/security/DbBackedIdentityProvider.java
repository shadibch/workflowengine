package com.wfe.security;

import com.wfe.core.port.IdentityProvider;
import com.wfe.persistence.identity.IamRoleRepository;
import com.wfe.persistence.identity.IamUserEntity;
import com.wfe.persistence.identity.IamUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The database-authoritative half of the hybrid identity model.
 *
 * <h2>Why roles are never read from the token</h2>
 * A JWT cannot be revoked. If authorization came from a role claim, removing a
 * user's access to a work queue would keep working until their token expired —
 * potentially hours. Here the token proves only <em>who</em> the caller is; every
 * authorization decision re-reads {@code iam_user_role} at the moment it is made,
 * so revoking a role is effective on the next request.
 *
 * <p>The cost is one authorization query per request. That is paid once, in
 * {@link com.wfe.security.DbJwtAuthenticationConverter}, and the resolved
 * {@link CurrentUser} is then carried on the {@code Authentication}, so the rest
 * of the request reads no further from the database.
 */
@Component
public class DbBackedIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(DbBackedIdentityProvider.class);

    private final IamUserRepository userRepository;
    private final IamRoleRepository roleRepository;
    private final TenantResolver tenantResolver;
    private final SecurityProperties properties;
    private final Clock clock;

    public DbBackedIdentityProvider(IamUserRepository userRepository,
                                    IamRoleRepository roleRepository,
                                    TenantResolver tenantResolver,
                                    SecurityProperties properties,
                                    Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantResolver = tenantResolver;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The caller for the current request.
     *
     * <p>Reads the principal already resolved during authentication rather than
     * querying again, so this is free. It is correct because the principal was
     * built moments ago in the same request.
     *
     * <p>Checks {@code getDetails()} as well as {@code getPrincipal()}: a
     * {@code JwtAuthenticationToken}'s principal is the {@code Jwt} itself, and the
     * database-resolved user is attached as details.
     */
    @Override
    public Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getDetails() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        if (authentication.getPrincipal() instanceof CurrentUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    @Override
    public CurrentUser requireCurrent() {
        return current().orElseThrow(() -> new UnauthenticatedCallerException(
                "This operation requires an authenticated user"));
    }

    /**
     * Re-reads authority straight from the database, bypassing the request
     * principal.
     *
     * <p>Used at the two points where a stale decision is a security incident
     * rather than a cosmetic issue: creating a task with candidate roles, and
     * claiming one. Between task creation and claim, minutes or days may pass and
     * a role may have been revoked; the claim must reflect now, not then.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public CurrentUser reload(Long userId) {
        IamUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthenticatedCallerException(
                        "User " + userId + " no longer exists"));
        if (!user.isActive()) {
            throw new DbJwtAuthenticationConverter.InactiveAccountException(
                    user.getUsername(), user.getStatus().name());
        }
        return resolve(user, user.getTenantId());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Set<Long> expandCandidateRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Set.of();
        }
        Set<String> assignable = roleRepository.findExistingAssignableCodes(
                tenantResolver.currentTenant(), roleCodes);
        if (assignable.size() != roleCodes.size()) {
            List<String> missing = roleCodes.stream().filter(code -> !assignable.contains(code)).toList();
            // Loud rather than silent: a typo in candidateGroups would otherwise
            // create a task nobody can claim, which surfaces days later as a
            // mysteriously stalled workflow.
            log.warn("Ignoring non-assignable or unknown candidate roles: {}", missing);
        }
        if (assignable.isEmpty()) {
            return Set.of();
        }
        return userRepository.findActiveUserIdsInRoles(
                tenantResolver.currentTenant(), List.copyOf(assignable), Instant.now(clock));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Long> findUserIdByUsername(String username) {
        return userRepository
                .findByTenantIdAndUsernameIgnoreCaseAndDeletedAtIsNull(
                        tenantResolver.currentTenant(), username)
                .map(IamUserEntity::getId);
    }

    /**
     * Whether a user still holds any of the candidate roles recorded on a task.
     * Called at claim time so a role revoked after the task was created blocks
     * the claim.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isCurrentCandidate(Long userId, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return false;
        }
        return userRepository.isCurrentMemberOfAny(
                userId, tenantResolver.currentTenant(), roleCodes, Instant.now(clock));
    }

    /**
     * Builds a {@link CurrentUser} for a database row.
     *
     * <p>Every grant query is evaluated against {@code now} with the validity
     * window applied in SQL, so the result cannot be skewed by a node whose clock
     * differs.
     */
    CurrentUser resolve(IamUserEntity user, String tenantId) {
        Instant now = Instant.now(clock);
        Set<String> roles = userRepository.findCurrentRoleCodes(user.getId(), tenantId, now);
        Set<String> permissions = userRepository.findCurrentPermissionCodes(user.getId(), tenantId, now);
        return new CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getExternalId(),
                user.getEmail(),
                roles,
                permissions,
                tenantId,
                user.getLocale(),
                user.getTimezone());
    }
}
