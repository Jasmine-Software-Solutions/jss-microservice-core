package com.jasminesoftwaresolutions.core.domain.models

import java.util.UUID

/**
 * A product-owned partition of a tenant's data.
 *
 * Deliberately carries **no** `workspaceId` of its own: a workspace is not
 * partitioned by a workspace. It is scoped by [tenantId] alone, which is what
 * makes tenant → workspaces one-to-many. Every other workspace-partitioned
 * domain model carries a `workspaceId` that points here.
 *
 * A service that previously had no partition below the tenant — a tenant id as
 * its schema's only ownership column, other tables reaching a tenant through a
 * parent row — gains one level of nesting: a tenant may hold several
 * independent workspaces, each with its own inventory.
 */
interface IWorkspace : IIdentified, ICreated {
    var tenantId: UUID
    var name: String
    var archived: Boolean
}
