package com.jasminesoftwaresolutions.core.auth.services

import com.jasminesoftwaresolutions.core.domain.services.IdTenant
import com.jasminesoftwaresolutions.core.domain.services.TenantDirectory
import com.jasminesoftwaresolutions.idclient.IDClient
import com.jasminesoftwaresolutions.idclient.api.TenantsApi

/**
 * Adapter from the identity service's client to [TenantDirectory].
 *
 * This is the only file in the shared workspace layer that names the identity
 * service's tenant API; workspace enumeration reads the service's own table.
 */
class IdTenantDirectory(private val idClient: IDClient) : TenantDirectory {
    override fun listTenants(accessToken: String): List<IdTenant> =
        TenantsApi(idClient, accessToken).listTenants().get()
            .map { IdTenant(it.id, it.name) }
}
