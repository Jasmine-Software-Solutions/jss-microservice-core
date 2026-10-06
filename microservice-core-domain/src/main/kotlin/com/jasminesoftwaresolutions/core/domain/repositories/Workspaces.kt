package com.jasminesoftwaresolutions.core.domain.repositories

import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import java.util.UUID

interface IWorkspaceRepository : IIdentifiedRepository<IWorkspace> {
    /**
     * All workspaces belonging to [tenantId] — active and archived alike.
     *
     * There is deliberately no `findByWorkspace` here: a workspace is the
     * partition, not a partitioned row. Callers that need to narrow to one
     * workspace use [findById] and compare [IWorkspace.tenantId].
     */
    fun findByTenant(tenantId: UUID): List<IWorkspace>
}
