package com.jasminesoftwaresolutions.core.server.infrastructure.repositories

import com.jasminesoftwaresolutions.core.server.infrastructure.testutil.DatabaseTestBase
import com.jasminesoftwaresolutions.core.server.infrastructure.testutil.ExposedWidgetRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `IWorkspaceScopedRepository.findById(tenantId, workspaceId, id)` — the scoped
 * single-row read, as inherited by every product repository.
 *
 * The interface and this default implementation are shared code now, so the
 * core module needs its own test of them; the products keep theirs, because
 * what those pin is as much their tables and adapters as the interface. The
 * stand-in aggregate here ([ExposedWidgetRepository]) exists so this module's
 * suite does not depend on a product.
 *
 * Each test asserts the *negative* first. A scoped read that simply returned
 * `findByIdUnscoped(id)` would pass a happy-path test; only the foreign-row
 * cases distinguish it.
 */
class WorkspaceScopedFindByIdTest : DatabaseTestBase() {

    private val widgets = ExposedWidgetRepository()

    // -- The negative case, which is the whole point ------------------------------

    @Test
    fun `a row in another workspace of the same tenant is not returned`() {
        val tenantId = newTenantId()
        val workspaceA = newWorkspaceId()
        val workspaceB = newWorkspaceId()

        val inB = widgets.create {
            this.tenantId = tenantId; this.workspaceId = workspaceB; this.name = "B-Widget"
        }

        // Same tenant — so a check that compared only `tenantId` would return
        // this row, and that is the near-miss worth pinning.
        assertNull(
            widgets.findById(tenantId, workspaceA, inB.id),
            "a same-tenant, foreign-workspace row was returned by the scoped read",
        )

        // Still there, and still reachable through its own workspace: the null
        // above is the predicate refusing, not the row having gone missing.
        assertNotNull(widgets.findById(tenantId, workspaceB, inB.id))
    }

    @Test
    fun `a row in another tenant is not returned`() {
        val workspaceId = newWorkspaceId()
        val inTenantB = widgets.create {
            this.tenantId = newTenantId(); this.workspaceId = workspaceId; this.name = "B"
        }

        assertNull(widgets.findById(newTenantId(), workspaceId, inTenantB.id))
    }

    @Test
    fun `a foreign row and a missing row are indistinguishable`() {
        val tenantId = newTenantId()
        val other = widgets.create {
            this.tenantId = tenantId; this.workspaceId = newWorkspaceId(); this.name = "Elsewhere"
        }

        // Both null, and callers turn null into a 404. If these ever diverged —
        // if a foreign row threw, or returned a sentinel — the response would
        // report that the id exists somewhere, which is the disclosure the
        // repartition is meant to remove.
        assertEquals(
            widgets.findById(tenantId, newWorkspaceId(), other.id),
            widgets.findById(tenantId, newWorkspaceId(), UUID.randomUUID()),
        )
    }

    // -- The positive case, so the negatives cannot pass trivially ----------------

    @Test
    fun `a row in scope is returned and the unscoped read still finds it`() {
        val tenantId = newTenantId()
        val workspaceId = newWorkspaceId()
        val widget = widgets.create {
            this.tenantId = tenantId; this.workspaceId = workspaceId; this.name = "Widget"
        }

        val scoped = widgets.findById(tenantId, workspaceId, widget.id)
        assertNotNull(scoped)
        assertEquals(widget.id, scoped!!.id)
        assertEquals("Widget", scoped.name)

        // The unscoped read still works, and it has a name of its own. The bare
        // `findById(id)` this used to call is `@Deprecated(level = ERROR)`, so
        // the only way to reach it is to spell `findByIdUnscoped` — which is the
        // backstop: a caller who skips the scope has to say so, and the
        // greppable count of these calls is the audit artifact. Asserting it
        // here keeps the two reads from being confused for each other in a
        // future edit.
        assertNotNull(widgets.findByIdUnscoped(widget.id))
    }

    @Test
    fun `the scope argument order is tenant then workspace`() {
        val tenantId = newTenantId()
        val workspaceId = newWorkspaceId()
        val widget = widgets.create {
            this.tenantId = tenantId; this.workspaceId = workspaceId; this.name = "Widget"
        }

        // Both arguments are bare UUIDs, so transposing them compiles and the
        // only symptom is a scoped read that always returns null — which looks
        // exactly like a permissions problem. This is the test that names them.
        assertNotNull(widgets.findById(tenantId, workspaceId, widget.id))
        assertNull(widgets.findById(workspaceId, tenantId, widget.id))
    }

    @Test
    fun `an id that exists nowhere returns null`() {
        assertNull(widgets.findById(newTenantId(), newWorkspaceId(), UUID.randomUUID()))
    }
}
