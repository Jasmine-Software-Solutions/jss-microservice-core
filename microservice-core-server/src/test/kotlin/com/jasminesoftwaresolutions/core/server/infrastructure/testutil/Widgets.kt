package com.jasminesoftwaresolutions.core.server.infrastructure.testutil

import com.jasminesoftwaresolutions.core.domain.models.IWorkspaceScoped
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceScopedRepository
import com.jasminesoftwaresolutions.core.server.infrastructure.util.entityPropertyDelegate
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * A minimal partitioned aggregate, used only by the tests.
 *
 * `IWorkspaceScopedRepository.findById(tenantId, workspaceId, id)` is library
 * code now, and its default implementation is what the products' repositories
 * inherit. The products' own scoped-read tests exercise it through their real
 * tables (and stay where they are, because what they pin is as much their
 * schema as the interface); this stand-in is what gives the core module a test
 * of the shared implementation that does not depend on a product.
 *
 * Four members and one column, deliberately: everything the scoped read needs
 * and nothing that could fail for an unrelated reason.
 */
interface IWidget : IWorkspaceScoped {
    var name: String
}

object WidgetsTable : UUIDTable("widgets") {
    val tenantId = uuid("tenant_id")
    val workspaceId = uuid("workspace_id")
    val name = varchar("name", 128)
}

class WidgetEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<WidgetEntity>(WidgetsTable)

    var tenantId by WidgetsTable.tenantId
    var workspaceId by WidgetsTable.workspaceId
    var name by WidgetsTable.name
}

internal class WidgetAdapter(val entity: WidgetEntity) : IWidget {
    override val id: UUID get() = entity.id.value

    override var tenantId: UUID by entityPropertyDelegate(entity, WidgetEntity::tenantId)
    override var workspaceId: UUID by entityPropertyDelegate(entity, WidgetEntity::workspaceId)
    override var name: String by entityPropertyDelegate(entity, WidgetEntity::name)
}

/** Hand-rolled like the products' scoped repositories, so `findByIdUnscoped` is the only unscoped read. */
class ExposedWidgetRepository : IWorkspaceScopedRepository<IWidget> {

    override fun findByIdUnscoped(id: UUID): IWidget? = transaction {
        WidgetEntity.findById(id)?.let { WidgetAdapter(it) }
    }

    override fun create(id: UUID, function: IWidget.() -> Unit): IWidget = transaction {
        var adapter: WidgetAdapter? = null
        WidgetEntity.new(id) {
            adapter = WidgetAdapter(this)
            function(adapter)
        }
        adapter!!
    }

    override fun update(entity: IWidget, function: IWidget.() -> Unit) = transaction {
        val adapter = entity as WidgetAdapter
        adapter.function()
    }

    override fun delete(entity: IWidget) = transaction {
        (entity as WidgetAdapter).entity.delete()
    }

    override fun install() = transaction {
        SchemaUtils.createMissingTablesAndColumns(WidgetsTable)
    }
}
