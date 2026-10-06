package com.jasminesoftwaresolutions.core.server.infrastructure.repositories

import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspaceEntity
import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspacesTable
import com.jasminesoftwaresolutions.core.server.infrastructure.repositories.models.WorkspaceAdapter
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * The Exposed-backed [IWorkspaceRepository], shared by every service.
 *
 * [installSchema] is the seam: a service's schema is installed in one call over
 * its whole table list (see the foreign-key note in `WorkspacesTable`), so this
 * repository cannot install its own table without reintroducing the per-table
 * install path that call replaced. The service passes its own
 * `installXSchema` function; nothing else about the repository varies.
 */
class ExposedWorkspaceRepository(
    private val installSchema: () -> Unit,
) : IWorkspaceRepository {

    override fun findById(id: UUID): IWorkspace? = transaction {
        WorkspaceEntity.findById(id)?.let { WorkspaceAdapter(it) }
    }

    override fun create(id: UUID, function: IWorkspace.() -> Unit): IWorkspace = transaction {
        var adapter: WorkspaceAdapter? = null
        WorkspaceEntity.new(id) {
            adapter = WorkspaceAdapter(this)
            function(adapter)
        }
        adapter!!
    }

    override fun update(entity: IWorkspace, function: IWorkspace.() -> Unit) = transaction {
        val adapter = entity as WorkspaceAdapter
        adapter.function()
    }

    override fun delete(entity: IWorkspace) = transaction {
        (entity as WorkspaceAdapter).entity.delete()
    }

    override fun install() = installSchema()

    override fun findByTenant(tenantId: UUID): List<IWorkspace> = transaction {
        WorkspaceEntity.find { WorkspacesTable.tenantId eq tenantId }.map { WorkspaceAdapter(it) }
    }
}
