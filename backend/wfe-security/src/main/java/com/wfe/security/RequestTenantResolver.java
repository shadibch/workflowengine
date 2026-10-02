package com.wfe.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Reads the tenant from the authenticated request, falling back to the configured
 * default for background work.
 *
 * <p>The fallback matters: timers, Kafka consumers and the simulation runner all
 * execute with no {@code SecurityContext}, and they still need a tenant to write
 * rows against. Guessing "default" there is correct for a single-tenant install
 * and is explicit rather than accidental.
 */
@Component
public class RequestTenantResolver implements TenantResolver {

    /** Claim names checked, in order, before falling back. */
    private static final String[] TENANT_CLAIMS = {"tenant_id", "tenantId", "tenant"};

    private final SecurityProperties properties;

    public RequestTenantResolver(SecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public String currentTenant() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            for (String claim : TENANT_CLAIMS) {
                Object value = token.getToken().getClaims().get(claim);
                if (value instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return properties.defaultTenantId();
    }

    @Override
    public String defaultTenant() {
        return properties.defaultTenantId();
    }
}
