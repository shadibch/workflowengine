package com.wfe.security;

/**
 * Resolves the tenant of the current unit of work.
 *
 * <p>An interface with a single-tenant implementation, because the schema already
 * carries {@code tenant_id} everywhere and the only thing missing is the plumbing.
 * When multi-tenancy arrives, this is where the request's tenant claim is read;
 * nothing else has to change.
 */
public interface TenantResolver {

    /**
     * The tenant for the current request, or the default for background work
     * (timers, message consumers, simulations) that has no request.
     */
    String currentTenant();

    /** The tenant used where no request context exists. */
    String defaultTenant();
}
