package com.wfe.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Security and identity configuration.
 *
 * @param issuerUri          OIDC issuer the API validates tokens against. The
 *                           JWKS is fetched from here and cached, so a Keycloak
 *                           restart does not invalidate existing tokens and an
 *                           unreachable issuer at boot does not stop the
 *                           application from starting.
 * @param audience           expected {@code aud}; empty disables the check, which
 *                           is only acceptable when the realm has a single client
 * @param requiredIssuer      alias for {@code issuerUri} kept for readability in
 *                           profiles
 * @param defaultTenantId     tenant used when the token carries no tenant claim.
 *                           Single-tenant deployments pin this.
 * @param clockSkew           tolerance for token time claims
 * @param allowedOrigins      CORS origins for the SPA
 * @param requireUserRow      when true, a valid token with no matching
 *                           {@code iam_user} row is rejected. This is the default:
 *                           the database is the authorization authority, so an
 *                           account that has been removed locally must lose access
 *                           immediately, not when its token expires. Set to false
 *                           only for a service-to-service token that has no user.
 * @param permissionsClaim    claim carrying permission codes, used only as a
 *                           cross-check - permissions are always re-read from the
 *                           database
 */
@ConfigurationProperties(prefix = "wfe.security")
public record SecurityProperties(
        String issuerUri,
        String audience,
        String defaultTenantId,
        java.time.Duration clockSkew,
        List<String> allowedOrigins,
        boolean requireUserRow,
        String permissionsClaim) {

    public SecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        clockSkew = clockSkew == null ? java.time.Duration.ofSeconds(30) : clockSkew;
        defaultTenantId = defaultTenantId == null || defaultTenantId.isBlank() ? "default" : defaultTenantId;
        audience = audience == null ? "" : audience;
    }
}
