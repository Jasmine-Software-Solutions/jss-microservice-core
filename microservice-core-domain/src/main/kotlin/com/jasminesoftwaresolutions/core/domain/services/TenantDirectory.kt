package com.jasminesoftwaresolutions.core.domain.services

import java.util.UUID

/** A tenant as the identity service reports it. */
data class IdTenant(val id: UUID, val name: String)

/**
 * The tenants a principal can reach, as told by the identity service.
 *
 * This port exists for testability: resolving a principal's listable workspaces
 * needs a tenant list, and without this seam the endpoint tests cannot supply
 * one without talking to ID.
 *
 * ID remains the only authority on which *tenants* exist. It is not consulted
 * about workspaces: those are service-owned rows, read from the service's own
 * database.
 */
interface TenantDirectory {
    fun listTenants(accessToken: String): List<IdTenant>
}
