package com.wfe.core.port;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the caller's identity and their effective roles.
 *
 * <p>This is the seam that implements the agreed hybrid identity model:
 * <b>the JWT authenticates, the database authorises.</b> A token proves who the
 * caller is; it never decides what they may do. Role grants are read from
 * {@code iam_user_role} at the moment of the decision, so revoking a role takes
 * effect on the next request rather than at token expiry — which is the whole
 * reason for not trusting the token's claims.
 *
 * <p>{@link CurrentUser} is therefore resolved once per request and cached for
 * that request only. It must never be cached across requests: a cached authority
 * decision is a revoked-permission bug waiting to happen.
 */
public interface IdentityProvider {

    /**
     * The authenticated caller.
     *
     * <p>{@code roles} and {@code permissions} are DB-resolved, not token-derived.
     */
    record CurrentUser(Long id, String username, String subject, String email,
                       Set<String> roles, Set<String> permissions, String tenantId,
                       String locale, String timezone) {

        public CurrentUser {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        public boolean hasRole(String roleCode) {
            return roles.contains(roleCode);
        }

        public boolean hasAnyRole(Set<String> roleCodes) {
            return roles.stream().anyMatch(roleCodes::contains);
        }

        public boolean hasPermission(String permissionCode) {
            return permissions.contains(permissionCode);
        }

        /**
         * A synthetic user for engine-internal work (timers, message consumers,
         * script tasks). It has no roles, so it can never satisfy a
         * permission-gated operation.
         */
        public static CurrentUser system(String tenantId) {
            return new CurrentUser(null, "system", null, null, Set.of(), Set.of(),
                    tenantId == null ? "default" : tenantId, "en", "UTC");
        }
    }

    /**
     * The current caller, or empty for an unauthenticated request (background
     * work, health endpoints, the simulation runner).
     */
    Optional<CurrentUser> current();

    /**
     * The current caller, or a failure when the request is unauthenticated. Use
     * at API boundaries where authentication is mandatory.
     */
    CurrentUser requireCurrent();

    /**
     * Re-reads roles and permissions for {@code userId} from the database,
     * bypassing any request-scoped cache.
     *
     * <p>Used at the two points where a stale authority decision would be
     * dangerous: creating a task with candidate roles, and claiming one.
     */
    CurrentUser reload(Long userId);

    /**
     * Expands a set of role codes into the users currently holding them, used
     * when a user task names candidate groups.
     *
     * <p>The result is snapshotted onto the task at creation time and re-validated
     * at claim time, so a later grant does not retroactively make an old task
     * visible and a later revocation blocks the claim.
     *
     * @param roleCodes role codes from the BPMN {@code candidateGroups}
     * @return ids of users with a currently-valid grant, for the given tenant
     */
    Set<Long> expandCandidateRoles(List<String> roleCodes);

    /**
     * Resolves a user by username. Used where BPMN names a specific assignee and
     * the definition was authored before the user existed.
     */
    Optional<Long> findUserIdByUsername(String username);
}
