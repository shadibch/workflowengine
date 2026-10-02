package com.wfe.security;

import com.wfe.core.port.IdentityProvider;
import com.wfe.persistence.identity.IamUserEntity;
import com.wfe.persistence.identity.IamUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a validated JWT into an authenticated principal whose authority came from
 * the database.
 *
 * <p>The token's own claims are used for <em>authentication</em> only: the subject
 * identifies the account, {@code preferred_username} and {@code email} are display
 * hints. Role and permission claims are deliberately <em>ignored</em>, even when
 * present — a Keycloak client that happens to emit a {@code roles} claim must not
 * be able to widen the application's authority. Grants are re-read from
 * {@code iam_user_role}, which is what makes a revocation immediate.
 *
 * <p>Authorities are exposed as {@code permission:code} so
 * {@code @PreAuthorize("hasAuthority('permission:task:claim')")} reads clearly and
 * cannot collide with a scope or a role name.
 */
@Component
public class DbJwtAuthenticationConverter
        implements org.springframework.core.convert.converter.Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(DbJwtAuthenticationConverter.class);

    private static final String AUTHORITY_PREFIX = "permission:";

    private final IamUserRepository userRepository;
    private final DbBackedIdentityProvider identityProvider;
    private final TenantResolver tenantResolver;
    private final SecurityProperties properties;
    private final Clock clock;

    public DbJwtAuthenticationConverter(IamUserRepository userRepository,
                                        DbBackedIdentityProvider identityProvider,
                                        TenantResolver tenantResolver,
                                        SecurityProperties properties,
                                        Clock clock) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
        this.tenantResolver = tenantResolver;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        String email = jwt.getClaimAsString("email");
        String tenantId = resolveTenant(jwt);

        Optional<IamUserEntity> user = findUser(subject, username, tenantId);

        if (user.isEmpty()) {
            if (properties.requireUserRow()) {
                // The database is the authority. An account removed locally must
                // lose access now, not when its token expires.
                throw new UnknownAccountException(subject, username, tenantId);
            }
            log.debug("No iam_user row for sub={} preferred_username={}; "
                    + "continuing without database authority (requireUserRow=false)", subject, username);
            return new JwtAuthenticationToken(jwt, List.of(), subject);
        }

        IamUserEntity account = user.get();
        if (!account.isActive()) {
            throw new InactiveAccountException(account.getUsername(), account.getStatus().name());
        }

        // First successful login for a row provisioned by username: adopt the
        // token's subject so every later request matches on the fast, unique
        // path. This is what makes the fallback self-healing.
        if ((account.getExternalId() == null || !account.getExternalId().equals(subject))
                && subject != null && !subject.isBlank()) {
            account.setExternalId(subject);
            userRepository.save(account);
        }

        IdentityProvider.CurrentUser principal = identityProvider.resolve(account, tenantId);

        // Keep the caller's own scopes/roles as a convenience for auditing, but
        // never as the source of an authorization decision.
        List<GrantedAuthority> authorities = new ArrayList<>(authoritiesFromJwt(jwt));
        for (String permission : principal.permissions()) {
            authorities.add(new SimpleGrantedAuthority(AUTHORITY_PREFIX + permission));
        }

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, authorities, subject);
        authentication.setDetails(principal);
        return authentication;
    }

    /**
     * The token's own authorities, used for logging and for the SPA's own
     * feature gating. Never consulted by a server-side decision.
     */
    private List<GrantedAuthority> authoritiesFromJwt(Jwt jwt) {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        List<GrantedAuthority> granted = new ArrayList<>(scopes.convert(jwt));
        List<String> claims = List.of("scope", "scp");
        for (String claim : claims) {
            Object raw = jwt.getClaims().get(claim);
            if (raw instanceof String s) {
                for (String part : s.split("[ ,]+")) {
                    if (!part.isBlank()) {
                        granted.add(new SimpleGrantedAuthority("SCOPE_" + part.trim()));
                    }
                }
            }
        }
        return granted;
    }

    /**
     * Resolves the tenant from a claim, defaulting when absent.
     *
     * <p>A tenant claim is honoured as-is; in a single-tenant deployment Keycloak
     * simply does not send one and everything lands on the default.
     */
    private String resolveTenant(Jwt jwt) {
        for (String claim : new String[]{"tenant_id", "tenantId", "tenant"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return tenantResolver.defaultTenant();
    }

    /**
     * Finds the account by subject, falling back to username.
     *
     * <p>The fallback exists because a definition may name an assignee by
     * username before that person has ever authenticated, leaving
     * {@code external_id} null. Matching on username is safe because the schema
     * makes it unique per tenant and the case-insensitive comparison mirrors how
     * administrators actually spell names.
     */
    private Optional<IamUserEntity> findUser(String subject, String username, String tenantId) {
        if (subject != null && !subject.isBlank()) {
            Optional<IamUserEntity> bySubject =
                    userRepository.findByTenantIdAndExternalIdAndDeletedAtIsNull(tenantId, subject);
            if (bySubject.isPresent()) {
                return bySubject;
            }
        }
        if (username != null && !username.isBlank()) {
            return userRepository.findByTenantIdAndUsernameIgnoreCaseAndDeletedAtIsNull(tenantId, username);
        }
        return Optional.empty();
    }

    /** A valid token whose subject matches no local account. */
    public static class UnknownAccountException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnknownAccountException(String subject, String username, String tenantId) {
            super("No account for subject '%s' (username '%s') in tenant '%s'"
                    .formatted(subject, username, tenantId));
        }
    }

    /** A local account that exists but is suspended or disabled. */
    public static class InactiveAccountException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InactiveAccountException(String username, String status) {
            super("Account '%s' is %s".formatted(username, status.toLowerCase(java.util.Locale.ROOT)));
        }
    }
}
