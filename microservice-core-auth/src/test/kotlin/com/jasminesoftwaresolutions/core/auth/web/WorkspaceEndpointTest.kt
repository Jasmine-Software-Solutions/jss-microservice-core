package com.jasminesoftwaresolutions.core.auth.web

import com.google.gson.JsonParser
import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.authorization.WorkspaceAuthentication
import com.jasminesoftwaresolutions.core.auth.testutil.FakeWorkspace
import com.jasminesoftwaresolutions.core.auth.testutil.TestResources
import com.jasminesoftwaresolutions.core.auth.testutil.testAuthConfig
import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IWorkspaceService
import com.jasminesoftwaresolutions.core.interfaces.GsonJsonMapper
import com.jasminesoftwaresolutions.core.server.infrastructure.util.GsonFactory
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import io.javalin.Javalin
import io.javalin.community.routing.annotations.AnnotatedRouting
import io.javalin.testtools.JavalinTest
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `WorkspaceController` — the management routes behind `requireAuth()`.
 *
 * These are the *ongoing* CRUD routes, not the bootstrap ones: every request
 * here arrives with a workspace already in scope. Creating a principal's first
 * workspace lives at `POST /api/v1/auth/workspaces` in
 * `WorkspaceBootstrapController`, which this harness cannot reach — it would
 * need a live session cookie, and `SessionAuthService.authenticateFromCookie`
 * introspects against the identity service. `WorkspaceServiceTest` covers the
 * property that makes that route possible instead: `createWorkspace` takes a
 * tenant and no workspace.
 *
 * The tests that matter most here are the four authorization ones. Each route
 * evaluates a *different* action against a *different* resource path, and the
 * pairs are easy to transpose — renaming gated on the tenant path instead of
 * the workspace path would still return 204 for every request in this file's
 * happy-path tests. The actions and paths asserted are the config's, which is
 * what a product injects; the product's own literal strings are pinned where
 * they are defined.
 */
class WorkspaceEndpointTest {

    private val workspaceService = mockk<IWorkspaceService>(relaxed = true)
    private val workspaceRepository = mockk<IWorkspaceRepository>(relaxed = true)
    private val auth = mockk<WorkspaceAuthentication>()
    private val config: WorkspaceAuthConfig = testAuthConfig()
    private val gson = GsonFactory.create() // production naming: snake_case on the wire

    private val tenantId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val principalId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        clearAllMocks()
        every { auth.tenantId } returns tenantId
        every { auth.workspaceId } returns workspaceId
        every { auth.principalId } returns principalId
        every { auth.evaluate(any(), any()) } returns true
    }

    private fun app(vararg controllers: Any): Javalin {
        val javalin = Javalin.create { config ->
            config.jsonMapper(GsonJsonMapper(gson))
            config.validation.register(UUID::class.java, UUID::fromString)
            config.router.mount(AnnotatedRouting) { it.registerEndpoints(*controllers) }
        }
        javalin.beforeMatched { ctx -> ctx.attribute("authContext", auth) }
        return javalin
    }

    private fun controller() = WorkspaceController(workspaceService, workspaceRepository, config)

    /** The single resource path `evaluate` was called with, as a string. */
    private fun evaluatedResource(action: String): String {
        val slot = slot<CompiledResourcePath>()
        verify { auth.evaluate(action, capture(slot)) }
        return slot.captured.toString()
    }

    // -- List ------------------------------------------------------------------------

    @Test
    fun `GET workspaces returns a flat array of the tenant's workspaces`() {
        val sales = FakeWorkspace(tenantId = tenantId, name = "Sales")
        val support = FakeWorkspace(tenantId = tenantId, name = "Support")
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(sales, support)

        JavalinTest.test(app(controller())) { _, http ->
            val res = http.get("/api/v1/workspaces")
            assertEquals(200, res.code)

            // A flat array, not grouped by tenant: the route is already scoped to
            // one tenant by the auth context, so a wrapper object would carry no
            // information. (The picker's /auth/workspaces groups multi-tenant.)
            val body = JsonParser.parseString(res.body!!.string()).asJsonArray
            assertEquals(2, body.size())

            // snake_case on the wire: GsonFactory uses LOWER_CASE_WITH_UNDERSCORES.
            // The frontend's axios client converts back to camelCase (see
            // frontend/src/client/index.ts), so `tenant_id` here is what the type
            // there calls `tenantId`.
            val first = body.first().asJsonObject
            assertEquals(sales.id.toString(), first.get("id").asString)
            assertEquals(tenantId.toString(), first.get("tenant_id").asString)
            assertEquals("Sales", first.get("name").asString)
            assertEquals(false, first.get("archived").asBoolean)
            assertEquals(0L, first.get("created_at").asLong)
        }
    }

    @Test
    fun `GET workspaces lists archived workspaces too`() {
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(
            FakeWorkspace(tenantId = tenantId, name = "Current"),
            FakeWorkspace(tenantId = tenantId, name = "Retired", archived = true),
        )

        JavalinTest.test(app(controller())) { _, http ->
            val body = JsonParser.parseString(http.get("/api/v1/workspaces").body!!.string()).asJsonArray

            // The management screen exists to show what it can act on, which
            // includes un-archiving. Filtering here would make an archived
            // workspace permanently invisible and so permanently unrecoverable.
            assertEquals(2, body.size())
            assertTrue(body.any { it.asJsonObject.get("archived").asBoolean })
        }
    }

    @Test
    fun `GET workspaces is gated on the config's list action at the tenant path`() {
        every { auth.evaluate(config.listWorkspaces, any()) } returns false

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(403, http.get("/api/v1/workspaces").code)
        }

        verify(exactly = 0) { workspaceRepository.findByTenant(any()) }
        assertEquals("${TestResources.tenant(tenantId)}", evaluatedResource(config.listWorkspaces))
    }

    @Test
    fun `GET workspaces does not require the workspace-scoped get action`() {
        // A management screen must be able to show a workspace in order to act
        // on it — including one the caller may not open. That is the whole
        // reason listing here is the list action alone, while the *picker* at
        // /auth/workspaces intersects the two. If this ever starts requiring
        // the get action, a principal can no longer see the workspace it needs
        // to grant itself access to.
        every { auth.evaluate(config.getWorkspace, any()) } returns false

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(200, http.get("/api/v1/workspaces").code)
        }
    }

    // -- Create ----------------------------------------------------------------------

    @Test
    fun `POST workspaces returns 201 with the created workspace`() {
        val created = FakeWorkspace(tenantId = tenantId, name = "Engineering")
        every { workspaceService.createWorkspace(principalId, tenantId, "Engineering") } returns created

        JavalinTest.test(app(controller())) { _, http ->
            val res = http.post("/api/v1/workspaces", mapOf("name" to "Engineering"))
            assertEquals(201, res.code)

            // 201 carries the body so a client need not re-list to learn the new
            // id — which matters because the next thing it does is select it.
            val body = JsonParser.parseString(res.body!!.string()).asJsonObject
            assertEquals(created.id.toString(), body.get("id").asString)
            assertEquals("Engineering", body.get("name").asString)
            assertEquals(tenantId.toString(), body.get("tenant_id").asString)
        }
    }

    @Test
    fun `POST workspaces rejects a missing name`() {
        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(400, http.post("/api/v1/workspaces", mapOf<String, Any>()).code)
        }
        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
    }

    @Test
    fun `POST workspaces is gated on the config's create action at the tenant path`() {
        every { auth.evaluate(config.createWorkspace, any()) } returns false

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(403, http.post("/api/v1/workspaces", mapOf("name" to "Nope")).code)
        }

        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
        assertEquals("${TestResources.tenant(tenantId)}", evaluatedResource(config.createWorkspace))
    }

    // -- Rename ----------------------------------------------------------------------

    @Test
    fun `POST workspaces id renames and returns 204`() {
        JavalinTest.test(app(controller())) { _, http ->
            val res = http.post("/api/v1/workspaces/$workspaceId", mapOf("name" to "Renamed"))
            assertEquals(204, res.code)
        }

        verify { workspaceService.renameWorkspace(principalId, tenantId, workspaceId, "Renamed") }
    }

    @Test
    fun `POST workspaces id passes the path id rather than the session's workspace`() {
        // `auth.workspaceId` is the workspace the caller is *in*; the path id is
        // the one being renamed, and for the management screen they differ. If
        // the controller ever substituted the session value here, an admin
        // managing workspaces would silently rename whichever one they happened
        // to have selected.
        val other = UUID.randomUUID()

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(204, http.post("/api/v1/workspaces/$other", mapOf("name" to "Renamed")).code)
        }

        verify { workspaceService.renameWorkspace(principalId, tenantId, other, "Renamed") }
    }

    @Test
    fun `POST workspaces id rejects a missing name`() {
        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(400, http.post("/api/v1/workspaces/$workspaceId", mapOf<String, Any>()).code)
        }
        verify(exactly = 0) { workspaceService.renameWorkspace(any(), any(), any(), any()) }
    }

    @Test
    fun `POST workspaces id is gated on the config's update action at the workspace path`() {
        every { auth.evaluate(config.updateWorkspace, any()) } returns false

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(403, http.post("/api/v1/workspaces/$workspaceId", mapOf("name" to "Nope")).code)
        }

        verify(exactly = 0) { workspaceService.renameWorkspace(any(), any(), any(), any()) }

        // The workspace path, not the tenant path. Renaming one workspace must
        // not be reachable by a grant that covers the tenant.
        assertEquals(
            "${TestResources.tenant(tenantId, workspaceId)}",
            evaluatedResource(config.updateWorkspace),
        )
    }

    // -- Archive ---------------------------------------------------------------------

    @Test
    fun `POST workspaces id archive returns 204`() {
        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(204, http.post("/api/v1/workspaces/$workspaceId/archive", mapOf<String, Any>()).code)
        }

        verify { workspaceService.archiveWorkspace(principalId, tenantId, workspaceId) }
    }

    @Test
    fun `POST workspaces id archive is gated on the config's delete action at the workspace path`() {
        every { auth.evaluate(config.deleteWorkspace, any()) } returns false

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(403, http.post("/api/v1/workspaces/$workspaceId/archive", mapOf<String, Any>()).code)
        }

        verify(exactly = 0) { workspaceService.archiveWorkspace(any(), any(), any()) }
        assertEquals(
            "${TestResources.tenant(tenantId, workspaceId)}",
            evaluatedResource(config.deleteWorkspace),
        )
    }

    @Test
    fun `archiving is gated on the delete action specifically, not the update action`() {
        // Archiving is the destructive one, so it is the pair most worth keeping
        // distinct: a tenant that grants rename rights has not thereby granted
        // the right to retire a workspace.
        every { auth.evaluate(config.deleteWorkspace, any()) } returns false
        every { auth.evaluate(config.updateWorkspace, any()) } returns true

        JavalinTest.test(app(controller())) { _, http ->
            assertEquals(403, http.post("/api/v1/workspaces/$workspaceId/archive", mapOf<String, Any>()).code)
        }

        verify(exactly = 0) { workspaceService.archiveWorkspace(any(), any(), any()) }
    }

    // -- Wire shape ------------------------------------------------------------------

    @Test
    fun `the create response is serialized with the production naming strategy`() {
        // The wire is snake_case — GsonFactory applies LOWER_CASE_WITH_UNDERSCORES,
        // and each frontend's axios client converts it back to camelCase. So the
        // keys below are the *inputs* to `toCamelCase`, and each has to be the
        // snake form of a field the frontend's hand-written `WorkspaceResponse`
        // declares: there is no generated client, so a renamed field is caught by
        // nothing at build time and surfaces as an `undefined` in the UI, not a
        // compile error.
        //
        // `created_at` is the one that would bite silently: the management page
        // renders it through formatDate, and an undefined there is a visible
        // "Invalid Date" rather than a blank.
        every { workspaceService.createWorkspace(any(), any(), any()) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app(controller())) { _, http ->
            val body = JsonParser.parseString(
                http.post("/api/v1/workspaces", mapOf("name" to "Sales")).body!!.string()
            ).asJsonObject

            assertEquals(
                setOf("id", "tenant_id", "name", "archived", "created_at"),
                body.keySet(),
                "the workspace wire shape changed; the frontends declare it by hand",
            )
        }
    }

    @Test
    fun `every wire key round-trips to a field the frontend declares`() {
        // `toCamelCase` in frontend/src/client/index.ts, restated. Asserting the
        // round-trip rather than the camelCase names alone is what makes the
        // previous test meaningful: `tenant_id` -> `tenantId` is the link, and a
        // key that failed it would reach the frontend as a field nobody reads.
        fun toCamelCase(key: String) = key.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }

        every { workspaceService.createWorkspace(any(), any(), any()) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app(controller())) { _, http ->
            val keys = JsonParser.parseString(
                http.post("/api/v1/workspaces", mapOf("name" to "Sales")).body!!.string()
            ).asJsonObject.keySet()

            assertEquals(
                setOf("id", "tenantId", "name", "archived", "createdAt"),
                keys.map(::toCamelCase).toSet(),
            )
        }
    }

    @Test
    fun `the list and create responses agree on the wire shape`() {
        // Both routes serialize the same DTO, but through different code paths —
        // a list and a single object. If one ever gains a field the other lacks,
        // the management screen would render a row differently depending on
        // whether it was just created or just loaded.
        val workspace = FakeWorkspace(tenantId = tenantId, name = "Sales")
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(workspace)
        every { workspaceService.createWorkspace(any(), any(), any()) } returns workspace

        JavalinTest.test(app(controller())) { _, http ->
            val listed = JsonParser.parseString(http.get("/api/v1/workspaces").body!!.string())
                .asJsonArray.first().asJsonObject
            val created = JsonParser.parseString(
                http.post("/api/v1/workspaces", mapOf("name" to "Sales")).body!!.string()
            ).asJsonObject

            assertEquals(listed.keySet(), created.keySet())
        }
    }

    @Test
    fun `a workspace from another tenant is not leaked by the list`() {
        // The controller scopes by the auth context's tenant, not by anything in
        // the request, so this asserts the repository was asked for the right
        // tenant — the controller adds no filter of its own.
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(FakeWorkspace(tenantId = tenantId))

        JavalinTest.test(app(controller())) { _, http ->
            http.get("/api/v1/workspaces")
        }

        verify { workspaceRepository.findByTenant(tenantId) }
        verify(exactly = 0) { workspaceRepository.findByTenant(neq(tenantId)) }
    }

    @Test
    fun `the service's returned workspace is what gets serialized`() {
        val created: IWorkspace = FakeWorkspace(tenantId = tenantId, name = "From The Service")
        every { workspaceService.createWorkspace(any(), any(), any()) } returns created

        JavalinTest.test(app(controller())) { _, http ->
            val body = JsonParser.parseString(
                http.post("/api/v1/workspaces", mapOf("name" to "Ignored")).body!!.string()
            ).asJsonObject

            assertEquals("From The Service", body.get("name").asString)
            assertEquals(created.id.toString(), body.get("id").asString)
        }
    }
}
