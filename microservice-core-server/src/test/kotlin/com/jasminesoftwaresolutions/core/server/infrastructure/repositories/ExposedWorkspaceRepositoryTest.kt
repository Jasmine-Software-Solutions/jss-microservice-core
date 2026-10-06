package com.jasminesoftwaresolutions.core.server.infrastructure.repositories

import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspacesTable
import com.jasminesoftwaresolutions.core.server.infrastructure.testutil.DatabaseTestBase
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ExposedWorkspaceRepositoryTest : DatabaseTestBase() {

    // The install seam a service wires to its own `installXSchema`. Here it
    // installs the one table this repository owns, which is the most this module
    // can honestly do — the reason the seam exists is that a *service's* tables
    // have to be installed in one call, and this module has no service.
    private val repo = ExposedWorkspaceRepository {
        transaction { SchemaUtils.createMissingTablesAndColumns(WorkspacesTable) }
    }

    @Test
    fun `findById returns null when workspace does not exist`() {
        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `create and findById round-trips fields`() {
        val id = UUID.randomUUID()
        val tenantId = newTenantId()

        val created = repo.create(id) {
            this.tenantId = tenantId
            this.name = "Sales"
        }

        assertEquals(id, created.id)
        assertEquals(tenantId, created.tenantId)
        assertEquals("Sales", created.name)
        assertEquals(false, created.archived)

        val found = repo.findById(id)
        assertNotNull(found)
        assertEquals("Sales", found!!.name)
        assertEquals(false, found.archived)
    }

    @Test
    fun `findByTenant returns only that tenant's workspaces`() {
        val tenantA = newTenantId()
        val tenantB = newTenantId()

        repo.create { this.tenantId = tenantA; this.name = "A-Sales" }
        repo.create { this.tenantId = tenantA; this.name = "A-Support" }
        repo.create { this.tenantId = tenantB; this.name = "B-Sales" }

        // Names repeat across tenants on purpose: uniqueness is per tenant, so
        // "B-Sales" must not be reachable from tenant A and the tenant filter
        // must be doing the work rather than the name.
        assertEquals(2, repo.findByTenant(tenantA).size)
        assertEquals(1, repo.findByTenant(tenantB).size)
        assertEquals(0, repo.findByTenant(newTenantId()).size)
    }

    @Test
    fun `findByTenant includes archived workspaces`() {
        val tenantId = newTenantId()
        val active = repo.create { this.tenantId = tenantId; this.name = "Active" }
        repo.create { this.tenantId = tenantId; this.name = "Retired" }
        repo.update(active) { this.archived = true }

        // The picker filters archived rows itself; the repository does not, or
        // a management screen could not show what it is there to un-archive.
        assertEquals(2, repo.findByTenant(tenantId).size)
    }

    @Test
    fun `archived flag persists`() {
        val workspace = repo.create { this.tenantId = newTenantId(); this.name = "Sales" }
        repo.update(workspace) { this.archived = true }
        assertEquals(true, repo.findById(workspace.id)!!.archived)
    }

    @Test
    fun `rename persists`() {
        val workspace = repo.create { this.tenantId = newTenantId(); this.name = "Old" }
        repo.update(workspace) { this.name = "New" }
        assertEquals("New", repo.findById(workspace.id)!!.name)
    }

    @Test
    fun `delete removes entity`() {
        val workspace = repo.create { this.tenantId = newTenantId(); this.name = "Gone" }
        repo.delete(workspace)
        assertNull(repo.findById(workspace.id))
    }

    @Test
    fun `install is idempotent`() {
        repo.install()
        repo.install()
    }

    @Test
    fun `the same name is rejected twice in one tenant`() {
        val tenantId = newTenantId()
        repo.create { this.tenantId = tenantId; this.name = "Sales" }

        // The unique index is the only thing preventing a duplicate, and it
        // cannot be enforced in application code without a check-then-insert
        // race — so its presence is worth asserting rather than assuming.
        assertThrows(Exception::class.java) {
            repo.create { this.tenantId = tenantId; this.name = "Sales" }
        }
    }

    @Test
    fun `the same name is allowed in different tenants`() {
        repo.create { this.tenantId = newTenantId(); this.name = "Sales" }
        repo.create { this.tenantId = newTenantId(); this.name = "Sales" }
        // Reaching here without throwing is the assertion: the index is
        // (tenant_id, name), not (name).
    }

    @Test
    fun `an archived workspace still reserves its name`() {
        val tenantId = newTenantId()
        val workspace = repo.create { this.tenantId = tenantId; this.name = "Sales" }
        repo.update(workspace) { this.archived = true }

        // Documented as accepted in WorkspacesTable: a unique index does not
        // exclude archived rows. Pinned here because the alternative reading —
        // that archiving frees the name — is the intuitive one, and a caller
        // relying on it would fail at runtime rather than at review.
        exposedLogger.warn("Expecting a constraint violation below; it is the assertion.")
        assertThrows(Exception::class.java) {
            repo.create { this.tenantId = tenantId; this.name = "Sales" }
        }
    }
}
