package com.jasminesoftwaresolutions.core.server.infrastructure.repositories

import com.jasminesoftwaresolutions.core.domain.models.IIdentified
import com.jasminesoftwaresolutions.core.domain.repositories.IIdentifiedRepository
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Base implementation of the standard Exposed create/update/delete/findById/install
 * repository pattern against a [UUIDEntityClass]. Subclasses supply an adapter
 * factory (entity → domain model) and an entity extractor (domain model → entity,
 * typically a cast to the concrete adapter) and only implement their extra queries.
 *
 * Usage:
 * ```
 * class ExposedPathRepository : IPathRepository,
 *     ExposedEntityRepository<IPath, PathEntity>(
 *         entityClass = PathEntity,
 *         table = PathsTable,
 *         adapter = ::PathAdapter,
 *         entityOf = { (it as PathAdapter).entity },
 *     ) {
 *     override fun findByOrg(tenantId: UUID, organizationId: UUID): List<IPath> = transaction {
 *         PathEntity.find { ... }.map { PathAdapter(it) }
 *     }
 * }
 * ```
 */
abstract class ExposedEntityRepository<T : IIdentified, E : UUIDEntity>(
    private val entityClass: UUIDEntityClass<E>,
    private val table: Table,
    private val adapter: (E) -> T,
    private val entityOf: (T) -> E,
) : IIdentifiedRepository<T> {

    override fun findById(id: UUID): T? = transaction {
        entityClass.findById(id)?.let(adapter)
    }

    override fun create(id: UUID, function: T.() -> Unit): T = transaction {
        var created: T? = null
        entityClass.new(id) {
            created = adapter(this).also { it.function() }
        }
        created!!
    }

    override fun update(entity: T, function: T.() -> Unit) = transaction {
        entity.function()
    }

    override fun delete(entity: T) = transaction {
        entityOf(entity).delete()
    }

    override fun install() = transaction {
        SchemaUtils.createMissingTablesAndColumns(table)
    }
}
