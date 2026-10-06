package com.jasminesoftwaresolutions.core.auth.services

import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.authorization.IEvaluationService
import com.jasminesoftwaresolutions.core.auth.testutil.FakeWorkspace
import com.jasminesoftwaresolutions.core.auth.testutil.TestResources
import com.jasminesoftwaresolutions.core.auth.testutil.testAuthConfig
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IdTenant
import com.jasminesoftwaresolutions.core.domain.services.TenantDirectory
import com.jasminesoftwaresolutions.idclient.IDClient
import com.jasminesoftwaresolutions.idclient.core.IDProvider
import com.jasminesoftwaresolutions.idclient.session.SessionStore
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The workspace visibility rule, pinned at the two predicates it is made of.
 *
 * ```
 * listWorkspaces → /tenant/{tenantId}                          (tenant-scoped)
 * getWorkspace   → /tenant/{tenantId}/workspace/{workspaceId}  (workspace-scoped)
 *
 * listable(w) = !archived && listWorkspaces on the tenant && getWorkspace on w
 * openable(w) = getWorkspace on w
 * ```
 *
 * The two grants sit on resource paths of **different length**, and the matcher
 * compares length exactly — so a grant at `/tenant/X` never covers
 * `/tenant/X/workspace/Y`. That independence is the whole mechanism, and these
 * tests exist because it is invisible: every combination below returns a
 * plausible-looking result, and only one of them is the specified one.
 *
 * The actions and paths come from an injected [WorkspaceAuthConfig] whose
 * service name is no product's, so a pass cannot come from a product string
 * surviving in the shared code; the service's *own* call sites are what prove
 * the injected values are the ones evaluated. Each product pins its literal
 * policy data where it is defined instead — those strings live in Witchwood's
 * database and a change to one changes what the deployed policies match.
 *
 * No gRPC channel is involved anywhere below: the service depends on
 * [IEvaluationService], so this file needs neither a `WitchwoodClient` subclass
 * nor the teardown one would require.
 */
class WorkspaceAuthorizationModelTest {

    private val principalId: UUID = UUID.randomUUID()
    private val accessToken = "test-access-token"

    private val tenantA: UUID = UUID.randomUUID()
    private val tenantB: UUID = UUID.randomUUID()

    private val sales = FakeWorkspace(tenantId = tenantA, name = "Sales")
    private val support = FakeWorkspace(tenantId = tenantA, name = "Support")
    private val marketing = FakeWorkspace(tenantId = tenantA, name = "Marketing")

    private val workspaceRepository = mockk<IWorkspaceRepository>()
    private val sessionStore = mockk<SessionStore>(relaxed = true)

    private val config: WorkspaceAuthConfig = testAuthConfig()

    /** The service under construction at the moment, so its calls are assertable. */
    private var evaluation = RecordingEvaluation(emptySet())

    /** Tenants ID reports, in order. */
    private var tenants: List<IdTenant> = listOf(IdTenant(tenantA, "Acme"), IdTenant(tenantB, "Globex"))

    // -- Fixture ---------------------------------------------------------------------

    private fun grant(action: String, resource: CompiledResourcePath) = RecordingEvaluation.key(action, resource)

    /**
     * A [SessionAuthService] backed by [grants], and nothing else.
     *
     * `IDClient` and `SessionStore` are only touched by the OAuth2 paths, which
     * these tests do not exercise — the tenant directory and the workspace
     * repository are the two collaborators the visibility rule actually reads.
     */
    private fun service(vararg grants: String): SessionAuthService {
        every { workspaceRepository.findByTenant(any()) } returns emptyList()
        every { workspaceRepository.findByTenant(tenantA) } returns listOf(sales, support, marketing)

        evaluation = RecordingEvaluation(grants.toSet())
        return SessionAuthService(
            redirectUri = "http://localhost/auth/callback",
            idClient = IDClient(IDProvider(URI("http://localhost")), UUID.randomUUID(), "secret"),
            sessionStore = sessionStore,
            evaluationService = evaluation,
            tenantDirectory = object : TenantDirectory {
                override fun listTenants(accessToken: String): List<IdTenant> = tenants
            },
            workspaceRepository = workspaceRepository,
            config = config,
        )
    }

    private fun SessionAuthService.listable() = resolveListableWorkspaces(principalId, accessToken)

    private fun SessionAuthService.openable(tenantId: UUID, workspaceId: UUID) =
        evaluate(principalId, config.getWorkspace, TestResources.tenant(tenantId, workspaceId))

    // -- The intersection ------------------------------------------------------------

    @Test
    fun `listWorkspaces alone lists nothing`() {
        val service = service(grant(config.listWorkspaces, TestResources.tenant(tenantA)))

        // The workspaces exist and the tenant-wide grant is present, so this is
        // the intersection's second half doing the work rather than an empty
        // fixture. See the next test for the same fixture with getWorkspace added.
        assertTrue(service.listable().isEmpty())

        // Such a principal is not stuck: it still reaches workspace management,
        // which is gated on the tenant-scoped grant rather than on this list.
        assertEquals(listOf(tenantA), service.resolveManageableTenants(principalId, accessToken).map { it.id })
    }

    @Test
    fun `listWorkspaces and getWorkspace together list exactly the granted workspace`() {
        val service = service(
            grant(config.listWorkspaces, TestResources.tenant(tenantA)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, sales.id)),
        )

        val listed = service.listable()

        // Marketing and Support are withheld by the absent second grant, not by
        // any explicit deny — an absent ALLOW and a DENY are indistinguishable
        // here, which is why hiding needs no extra signal.
        assertEquals(1, listed.size)
        assertEquals(sales.id, listed.single().workspaceId)

        // The picker renders the names, so both halves of the pairing have to
        // survive: the workspace is this service's own row, the tenant name
        // comes from ID.
        assertEquals("Sales", listed.single().workspaceName)
        assertEquals(tenantA, listed.single().tenantId)
        assertEquals("Acme", listed.single().tenantName)
    }

    @Test
    fun `getWorkspace alone is not listed but is openable`() {
        val service = service(grant(config.getWorkspace, TestResources.tenant(tenantA, sales.id)))

        // Not listed: this is the deliberate API-only case. The workspace is
        // reachable and usable by a client that knows its id, and invisible in
        // the UI — and such a principal holds no managing grant, so it lands on
        // the 404 empty state rather than a management screen.
        assertTrue(service.listable().isEmpty())

        // Openable: the identical call `resolveAuthContext` makes to decide
        // whether a request may address this workspace. Same action, same
        // resource, same builder — so the two halves of the model are genuinely
        // independent rather than one being a side effect of the other.
        assertTrue(service.openable(tenantA, sales.id))
        assertFalse(service.openable(tenantA, support.id))
    }

    @Test
    fun `getWorkspace denied hides one workspace while the tenant-wide grant is present`() {
        val service = service(
            grant(config.listWorkspaces, TestResources.tenant(tenantA)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, support.id)),
        )

        // This is the hiding behaviour, and the case that would break under a
        // model where a tenant-wide listWorkspaces implied visibility of every
        // workspace under it: Sales and Marketing are absent despite the
        // tenant-wide grant being present and allowed.
        assertEquals(listOf(support.id), service.listable().map { it.workspaceId })
    }

    @Test
    fun `an archived workspace is not listable even with both grants`() {
        // Both halves granted on both rows, so the only thing withholding
        // Support is its archive flag — this is the picker showing a workspace
        // that was archived, the bug this pins. Archived is checked on top of
        // the intersection, not instead of it.
        support.archived = true
        val service = service(
            grant(config.listWorkspaces, TestResources.tenant(tenantA)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, sales.id)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, support.id)),
        )

        assertEquals(listOf(sales.id), service.listable().map { it.workspaceId })

        // The archive flag is not a revocation: the row stays openable, which is
        // what lets a session already inside it keep working until the next
        // load re-selects — and what keeps the management view, which lists
        // archived rows directly from the repository, able to act on it.
        assertTrue(service.openable(tenantA, support.id))
    }

    @Test
    fun `a tenant without listWorkspaces is skipped before its workspaces are read`() {
        val service = service(
            grant(config.listWorkspaces, TestResources.tenant(tenantA)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, sales.id)),
            // Tenant B: getWorkspace granted on a workspace, but the tenant-scoped
            // half withheld. The inner loop must never run for B.
            grant(config.getWorkspace, TestResources.tenant(tenantB, marketing.id)),
        )

        assertEquals(listOf(sales.id), service.listable().map { it.workspaceId })

        // The hoisted tenant check is what makes this a skip rather than a scan
        // that discards. If the predicate were moved inside the loop the list
        // would be identical, so the call count is the only thing that pins it.
        verify(exactly = 0) { workspaceRepository.findByTenant(tenantB) }
        verify(exactly = 1) { workspaceRepository.findByTenant(tenantA) }
    }

    @Test
    fun `the tenant-scoped grant is evaluated once per tenant, not once per workspace`() {
        val service = service(
            grant(config.listWorkspaces, TestResources.tenant(tenantA)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, sales.id)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, support.id)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, marketing.id)),
        )

        assertEquals(3, service.listable().size)

        // Three workspaces under one tenant, and the listWorkspaces evaluation
        // does not depend on the workspace — so it is hoisted out of the inner
        // loop. In a permissions check this is a per-request latency question,
        // not a correctness one, which is exactly why it needs a test.
        val tenantScoped = evaluation.calls.count { it.first == config.listWorkspaces }
        assertEquals(2, tenantScoped, "expected one listWorkspaces evaluation per tenant (A and B)")
    }

    @Test
    fun `nothing is listed when no tenant clears the first half`() {
        // Both tenants are returned by ID and all three workspaces exist; the
        // principal simply holds nothing. Empty, not an error — the empty list is
        // a valid answer that the frontend turns into the empty state.
        val service = service()

        assertTrue(service.listable().isEmpty())
        assertTrue(service.resolveManageableTenants(principalId, accessToken).isEmpty())
    }

    @Test
    fun `the visibility list is never answered by queryResources`() {
        // `queryResources` returns stored ALLOW *patterns* verbatim and does not
        // expand a tenant-level allow into workspace paths, so it cannot answer
        // this question — it is a hint at best and never an authorization
        // decision. Pinned because "replace the N evaluations with one query" is
        // exactly the optimisation someone will attempt, and it would silently
        // change what is listed.
        val service = service(
            grant(config.listWorkspaces, TestResources.tenant(tenantA)),
            grant(config.getWorkspace, TestResources.tenant(tenantA, sales.id)),
        )

        assertEquals(listOf(sales.id), service.listable().map { it.workspaceId })
        assertEquals(0, evaluation.queryCalls)
    }

    // -- Manageability, which decides the empty state ---------------------------------

    @Test
    fun `createWorkspace alone makes a tenant manageable`() {
        // The bootstrap case: a principal that may create the first workspace but
        // has none to list. Without this, the empty state would be a 404 and the
        // very principal able to fix it would be the one locked out.
        val service = service(grant(config.createWorkspace, TestResources.tenant(tenantA)))

        assertTrue(service.listable().isEmpty())
        assertEquals(listOf(tenantA), service.resolveManageableTenants(principalId, accessToken).map { it.id })
    }

    @Test
    fun `manageable tenants are filtered, not every tenant ID reports`() {
        val service = service(grant(config.listWorkspaces, TestResources.tenant(tenantB)))

        // Both tenants come back from ID; only one carries a managing grant.
        // Returning the unfiltered list would hand a tenant name to a principal
        // with no grant there *and* leave the frontend unable to tell the two
        // empty states apart, since "can manage" is exactly this predicate.
        assertEquals(listOf(tenantB), service.resolveManageableTenants(principalId, accessToken).map { it.id })
    }

    @Test
    fun `workspace-scoped grants alone do not make a tenant manageable`() {
        // updateWorkspace and deleteWorkspace sit on the workspace path, not the
        // tenant path. A principal holding only those has no workspace to exercise
        // them on and nothing to show, so they must not open the management screen.
        val service = service(
            grant(config.updateWorkspace, TestResources.tenant(tenantA, sales.id)),
            grant(config.deleteWorkspace, TestResources.tenant(tenantA, sales.id)),
        )

        assertTrue(service.resolveManageableTenants(principalId, accessToken).isEmpty())
    }

    // -- The contract the policies are authored against ------------------------------

    @Test
    fun `the resource path shape is what the policies match on`() {
        // The literal form, spelled out once. Every other test in this file builds
        // its grants through the config's `tenantPath`, which would move both
        // sides of an assertion together if a segment were renamed — this is the
        // test that fails instead.
        //
        // The strings below are the *test* config's; what they pin is the shape
        // every product's builder has to produce. Each product pins its own
        // literal service name and action strings where they are defined,
        // because those are the bytes the policies in Witchwood's database were
        // authored against, and `workspace` replacing `organization` is a hard
        // break rather than a graceful rename.
        assertEquals(
            "test.jasmine.software://tenant:$tenantA",
            TestResources.tenant(tenantA).toString(),
        )
        assertEquals(
            "test.jasmine.software://tenant:$tenantA/workspace:${sales.id}",
            TestResources.tenant(tenantA, sales.id).toString(),
        )

        // The tenant-only path has no workspace segment at all — which is what
        // makes listWorkspaces tenant-wide, and what makes
        // `POST /auth/workspaces` answerable before a workspace exists.
        assertFalse(TestResources.tenant(tenantA).toString().contains("workspace"))
    }

    @Test
    fun `the workspace path does not match the tenant path`() {
        // Different length, exact-length comparison: the two grants are
        // independent. Asserted directly because the whole model rests on it and
        // a `**` where a `*` belongs would silently collapse the distinction.
        assertNotEquals(TestResources.tenant(tenantA), TestResources.tenant(tenantA, sales.id))
        assertNotEquals(
            TestResources.tenant(tenantA).toString(),
            TestResources.tenant(tenantA, sales.id).toString(),
        )
    }

    /** Records what it was asked, so call counts are assertable. */
    private class RecordingEvaluation(private val granted: Set<String>) : IEvaluationService {

        val calls = mutableListOf<Pair<String, CompiledResourcePath>>()

        var queryCalls = 0
            private set

        override fun evaluate(
            principalId: UUID,
            action: String,
            resource: CompiledResourcePath,
            context: Map<String, String>,
        ): CompletableFuture<Boolean> {
            calls += action to resource
            return CompletableFuture.completedFuture(key(action, resource) in granted)
        }

        override fun queryResources(
            principalId: UUID,
            action: String,
        ): CompletableFuture<Set<CompiledResourcePath>> {
            queryCalls++
            return CompletableFuture.completedFuture(emptySet())
        }

        companion object {
            fun key(action: String, resource: CompiledResourcePath) = "$action @ $resource"
        }
    }
}
