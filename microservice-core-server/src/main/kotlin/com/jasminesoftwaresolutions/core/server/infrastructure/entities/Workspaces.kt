package com.jasminesoftwaresolutions.core.server.infrastructure.entities

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.UUID

/**
 * The workspace partition root. Every other partitioned table's `workspaceId`
 * points here.
 *
 * Ordering matters in two places, because this table must exist before anything
 * referencing it does:
 *  - the service's table list must name it **first** (SQLite needs parents
 *    before children), and
 *  - the service installs its schema in one call, which handles the ordering
 *    for Postgres, where `REFERENCES workspaces(id)` fails otherwise.
 *
 * Every partitioned table declares `foreignKey(workspaceId to WorkspacesTable.id,
 * … RESTRICT)` in its own `init`. That declaration is load-bearing and not
 * redundant with the workspace migration: the migration only ever runs
 * `ALTER TABLE IF EXISTS`, so on a **fresh** Postgres database it is a no-op and
 * Exposed creates the schema unaided — meaning the constraint exists on that
 * path *only* if it is declared there. Deleting one of those `foreignKey` calls
 * silently costs the constraint on every newly provisioned environment while
 * leaving migrated ones intact, which is the kind of divergence that stays
 * invisible until a workspace with orphaned rows is deleted.
 *
 * Note there is no `workspaceId` column: a workspace is scoped by tenant alone.
 */
object WorkspacesTable : UUIDTable("workspaces") {
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val tenantId = uuid("tenant_id")
    val name = varchar("name", 128)
    val archived = bool("archived").default(false)

    init {
        // Names are unique per tenant. This must match the workspace migration's
        // index exactly, so a migrated database and a freshly created one agree;
        // Exposed names indexes "<table>_<col>_<col>", with no marker for the
        // uniqueness, and the migration mirrors that.
        //
        // Caveat: a unique index does not exclude archived rows, so archiving
        // "Sales" permanently reserves that name for the tenant. Accepted — the
        // database is the only place this can be enforced without a
        // check-then-insert race.
        index(true, tenantId, name)
    }
}

class WorkspaceEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<WorkspaceEntity>(WorkspacesTable)

    var createdAt by WorkspacesTable.createdAt
    var tenantId by WorkspacesTable.tenantId
    var name by WorkspacesTable.name
    var archived by WorkspacesTable.archived
}
