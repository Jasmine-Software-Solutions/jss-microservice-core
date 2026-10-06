package com.jasminesoftwaresolutions.core.auth.services

import com.jasminesoftwaresolutions.core.auth.testutil.DatabaseTestBase
import com.jasminesoftwaresolutions.core.server.infrastructure.entities.WorkspacesTable
import com.jasminesoftwaresolutions.core.server.infrastructure.repositories.ExposedWorkspaceRepository
import io.javalin.http.NotFoundResponse
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The tenant bound on workspace mutations.
 *
 * `renameWorkspace` and `archiveWorkspace` look a workspace up by id alone, so
 * the only thing stopping a caller from mutating another tenant's workspace by
 * guessing its id is the `takeIf { it.tenantId == tenantId }` in each. The
 * controller checks authorization against the (tenantId, workspaceId) pair it
 * was given, which is only sound if the lookup agrees — these tests pin that.
 *
 * Run against the real [ExposedWorkspaceRepository] rather than a double, so
 * the row that comes back is the one a production lookup would produce — the
 * foreign-tenant cases assert on a value the repository actually returned.
 */
class WorkspaceServiceTest : DatabaseTestBase() {

    private val repo = ExposedWorkspaceRepository {
        transaction { SchemaUtils.createMissingTablesAndColumns(WorkspacesTable) }
    }
    private val service = WorkspaceService(repo)

    private val principalId: UUID = UUID.randomUUID()

    @Test
    fun `createWorkspace scopes the row to the tenant it was given`() {
        val tenantId = UUID.randomUUID()
        val created = service.createWorkspace(principalId, tenantId, "Sales")

        assertEquals(tenantId, created.tenantId)
        assertEquals("Sales", created.name)
        assertEquals(false, created.archived)
    }

    @Test
    fun `createWorkspace does not require the principal to have a workspace`() {
        // The bootstrap case: the principal is authenticated but addresses no
        // workspace, because none exists. Nothing in the tenant argument can
        // supply one, which is the point.
        val created = service.createWorkspace(principalId, UUID.randomUUID(), "First")
        assertNotNull(repo.findById(created.id))
    }

    @Test
    fun `renameWorkspace renames within its own tenant`() {
        val tenantId = UUID.randomUUID()
        val workspace = service.createWorkspace(principalId, tenantId, "Old")

        service.renameWorkspace(principalId, tenantId, workspace.id, "New")

        assertEquals("New", repo.findById(workspace.id)!!.name)
    }

    @Test
    fun `renameWorkspace refuses a workspace belonging to another tenant`() {
        val owner = UUID.randomUUID()
        val attacker = UUID.randomUUID()
        val workspace = service.createWorkspace(principalId, owner, "Untouched")

        assertThrows(NotFoundResponse::class.java) {
            service.renameWorkspace(principalId, attacker, workspace.id, "Hijacked")
        }

        assertEquals("Untouched", repo.findById(workspace.id)!!.name)
    }

    @Test
    fun `archiveWorkspace refuses a workspace belonging to another tenant`() {
        val owner = UUID.randomUUID()
        val attacker = UUID.randomUUID()
        val workspace = service.createWorkspace(principalId, owner, "Untouched")

        assertThrows(NotFoundResponse::class.java) {
            service.archiveWorkspace(principalId, attacker, workspace.id)
        }

        assertEquals(false, repo.findById(workspace.id)!!.archived)
    }

    @Test
    fun `renameWorkspace on a missing id is not found`() {
        assertThrows(NotFoundResponse::class.java) {
            service.renameWorkspace(principalId, UUID.randomUUID(), UUID.randomUUID(), "New")
        }
    }

    @Test
    fun `archiveWorkspace on a missing id is not found`() {
        assertThrows(NotFoundResponse::class.java) {
            service.archiveWorkspace(principalId, UUID.randomUUID(), UUID.randomUUID())
        }
    }

    @Test
    fun `listWorkspaces returns only the requested tenant`() {
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        service.createWorkspace(principalId, tenantA, "A1")
        service.createWorkspace(principalId, tenantA, "A2")
        service.createWorkspace(principalId, tenantB, "B1")

        assertEquals(2, service.listWorkspaces(tenantA).size)
        assertEquals(1, service.listWorkspaces(tenantB).size)
        assertTrue(service.listWorkspaces(tenantA).all { it.tenantId == tenantA })
    }
}
