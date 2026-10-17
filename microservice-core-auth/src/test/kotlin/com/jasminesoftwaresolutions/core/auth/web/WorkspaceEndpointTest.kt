package com.jasminesoftwaresolutions.core.auth.web

import com.google.gson.JsonParser
import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.services.SessionAuthService
import com.jasminesoftwaresolutions.core.auth.testutil.FakeWorkspace
import com.jasminesoftwaresolutions.core.auth.testutil.RecordingEvaluation
import com.jasminesoftwaresolutions.core.auth.testutil.TestResources
import com.jasminesoftwaresolutions.core.auth.testutil.testAuthConfig
import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IWorkspaceService
import com.jasminesoftwaresolutions.core.domain.services.IdTenant
import com.jasminesoftwaresolutions.core.domain.services.TenantDirectory
import com.jasminesoftwaresolutions.core.interfaces.GsonJsonMapper
import com.jasminesoftwaresolutions.core.server.infrastructure.util.GsonFactory
import com.jasminesoftwaresolutions.idclient.IDClient
import com.jasminesoftwaresolutions.idclient.core.IDProvider
import com.jasminesoftwaresolutions.idclient.session.SessionStore
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import io.javalin.Javalin
import io.javalin.community.routing.annotations.AnnotatedRouting
import io.javalin.testtools.JavalinTest
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.UUID

/**
 * `WorkspaceController` — all seven routes, over the **real**
 * [SessionAuthService].
 *
 * One controller serves what used to be two, so this file covers the picker and
 * selection routes for the first time: they were unreachable from a test while
 * they authenticated from a session cookie inside a controller that took a
 * concrete `SessionAuthService`, and the service itself is not mockable (a
 * final Kotlin class, which mockk cannot instrument under this toolchain).
 * Driving the real one is the better harness anyway — the grants below are the
 * ones the routes actually evaluate, so a route that evaluated the wrong action
 * or the wrong path fails here rather than being quietly stubbed away.
 *
 * The principal reaches the service the way it does in production: the
 * before-filter's attributes, which `authenticate` prefers over introspecting
 * again. Everything below that — the gates — is real.
 *
 * The tests that matter most are the authorization ones. Each route evaluates a
 * *different* action against a *different* resource path, and the pairs are easy
 * to transpose: a rename gated on the tenant path would still answer 204 for
 * every happy-path request here. The sharpest is the tenant a write is gated on
 * — the gate must follow **the tenant being written to**, which is why several
 * tests below write into a tenant the session's grants do not cover.
 */
class WorkspaceEndpointTest {

    private val workspaceService = mockk<IWorkspaceService>(relaxed = true)
    private val workspaceRepository = mockk<IWorkspaceRepository>(relaxed = true)
    private val sessionStore = mockk<SessionStore>(relaxed = true)

    private val config: WorkspaceAuthConfig = testAuthConfig()
    private val gson = GsonFactory.create() // production naming: snake_case on the wire

    private val tenantId = UUID.randomUUID()
    private val otherTenantId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val principalId = UUID.randomUUID()
    private val accessToken = "test-access-token"

    /** The tenants Identity reports, in order. */
    private var tenants: List<IdTenant> = emptyList()

    /** What the service under construction evaluates against — see [app]. */
    private lateinit var evaluation: RecordingEvaluation

    @BeforeEach
    fun setup() {
        clearAllMocks()
        tenants = listOf(IdTenant(tenantId, "Acme"))

        // Stubbed rather than left to `relaxed`: for a nullable *generic* return
        // like `IWorkspace?`, mockk's relaxed answer is a mock of the erased
        // bound — an `IIdentified` that fails a cast the moment a handler reads
        // `tenantId` — so an unstubbed call would surface as a 500 rather than
        // the 404 every route's unknown-id path expects. "No such row" is the
        // default here; a test that wants one says so.
        every { workspaceRepository.findById(any()) } returns null
    }

    // -- Harness ---------------------------------------------------------------------

    /**
     * The app under test, holding exactly [grants] and nothing else.
     *
     * A grant is `RecordingEvaluation.key(action, path)`; [grant] builds one, and
     * [sessionGrants] is the set an ordinary management session holds. A denial
     * is therefore the *absence* of a key, which is what a policy store answers
     * too — there is no separate "deny" to remember.
     */
    private fun app(vararg grants: String): Javalin {
        evaluation = RecordingEvaluation(grants.toSet())

        val sessionAuth = SessionAuthService(
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

        // `javalinConfig` rather than `config`, which is the policy data this
        // class holds — the lambda parameter would shadow it.
        val javalin = Javalin.create { javalinConfig ->
            javalinConfig.jsonMapper(GsonJsonMapper(gson))
            javalinConfig.validation.register(UUID::class.java, UUID::fromString)
            javalinConfig.router.mount(AnnotatedRouting) {
                it.registerEndpoints(
                    WorkspaceController(sessionAuth, workspaceService, workspaceRepository, config)
                )
            }
        }

        // The before-filter, reduced to what these routes read: the pair it
        // resolved. Every product's filter leaves exactly this, and
        // `authenticate` prefers it over asking ID a second time.
        javalin.before { ctx ->
            ctx.attribute("principalId", principalId)
            ctx.attribute("accessToken", accessToken)
        }

        return javalin
    }

    private fun grant(action: String, resource: CompiledResourcePath) =
        RecordingEvaluation.key(action, resource)

    /** List and create on the session's tenant, and the workspace pair on [workspaceId]. */
    private fun sessionGrants(vararg extra: String): Array<String> = arrayOf(
        grant(config.listWorkspaces, TestResources.tenant(tenantId)),
        grant(config.createWorkspace, TestResources.tenant(tenantId)),
        grant(config.getWorkspace, TestResources.tenant(tenantId, workspaceId)),
        grant(config.updateWorkspace, TestResources.tenant(tenantId, workspaceId)),
        grant(config.deleteWorkspace, TestResources.tenant(tenantId, workspaceId)),
        *extra,
    )

    /** The paths [action] was evaluated at, in order. */
    private fun evaluatedResources(action: String): List<String> =
        evaluation.calls.filter { it.first == action }.map { it.second.toString() }

    /** The single path [action] was evaluated at. */
    private fun evaluatedResource(action: String): String = evaluatedResources(action).single()

    // -- The picker: what a principal may open ---------------------------------------

    @Test
    fun `GET auth workspaces answers the picker's rows`() {
        val sales = FakeWorkspace(tenantId = tenantId, name = "Sales")
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(sales)

        val app = app(
            grant(config.listWorkspaces, TestResources.tenant(tenantId)),
            grant(config.getWorkspace, TestResources.tenant(tenantId, sales.id)),
        )

        JavalinTest.test(app) { _, http ->
            val res = http.get("/api/v1/auth/workspaces")
            assertEquals(200, res.code)

            // The picker's row carries the tenant *name* as well as the id: the
            // route spans tenants, and the switcher renders the name.
            val row = JsonParser.parseString(res.body!!.string()).asJsonArray.first().asJsonObject
            assertEquals(setOf("tenant_id", "tenant_name", "workspace_id", "workspace_name"), row.keySet())
            assertEquals("Acme", row.get("tenant_name").asString)
            assertEquals("Sales", row.get("workspace_name").asString)
        }
    }

    @Test
    fun `GET auth workspaces answers an empty array for a principal that may open nothing`() {
        // Not an error and not a dead end: the frontend pairs this with
        // /auth/tenants to decide between management and a 404.
        every { workspaceRepository.findByTenant(any()) } returns
            listOf(FakeWorkspace(tenantId = tenantId, name = "Sales"))

        JavalinTest.test(app()) { _, http ->
            val res = http.get("/api/v1/auth/workspaces")
            assertEquals(200, res.code)
            assertEquals(0, JsonParser.parseString(res.body!!.string()).asJsonArray.size())
        }
    }

    @Test
    fun `GET auth tenants answers the manageable set`() {
        val app = app(grant(config.createWorkspace, TestResources.tenant(tenantId)))

        JavalinTest.test(app) { _, http ->
            val row = JsonParser.parseString(http.get("/api/v1/auth/tenants").body!!.string())
                .asJsonArray.first().asJsonObject

            assertEquals(tenantId.toString(), row.get("tenant_id").asString)
            assertEquals("Acme", row.get("tenant_name").asString)
        }
    }

    // -- Selection --------------------------------------------------------------------

    @Test
    fun `POST auth workspaces select writes the selection and returns 204`() {
        val app = app(grant(config.getWorkspace, TestResources.tenant(tenantId, workspaceId)))

        JavalinTest.test(app) { _, http ->
            val res = http.post(
                "/api/v1/auth/workspaces/select",
                mapOf(
                    "tenant_id" to tenantId.toString(),
                    "workspace_id" to workspaceId.toString(),
                    "tenant_name" to "Acme",
                    "workspace_name" to "Sales",
                ),
            )
            assertEquals(204, res.code)

            // Both cookies, httpOnly — this route is the only writer of the
            // selection, and writing it is the whole of what it does.
            val setCookie = res.headers("Set-Cookie")
            for (name in listOf("${config.cookieNamespace}_tenant", "${config.cookieNamespace}_workspace")) {
                val header = setCookie.singleOrNull { it.startsWith("$name=") }
                assertNotNull(header, "$name was not set")
                assertTrue(header!!.contains("HttpOnly"), "$name must be HttpOnly: $header")
            }
        }

        // Selecting is opening, so it is gated exactly as addressing is — and on
        // the pair named in the body, not on anything the session already holds.
        assertEquals(
            "${TestResources.tenant(tenantId, workspaceId)}",
            evaluatedResource(config.getWorkspace),
        )
    }

    @Test
    fun `POST auth workspaces select answers 404 rather than 403 when the grant is missing`() {
        JavalinTest.test(app()) { _, http ->
            val res = http.post(
                "/api/v1/auth/workspaces/select",
                mapOf("tenant_id" to tenantId.toString(), "workspace_id" to workspaceId.toString()),
            )
            // 404, not 403: a principal with no grant should not be able to tell
            // a workspace that exists from one that does not.
            assertEquals(404, res.code)

            // And no partial selection: a refused call writes nothing.
            assertTrue(res.headers("Set-Cookie").none { it.startsWith("${config.cookieNamespace}_tenant=") })
        }
    }

    // -- List ------------------------------------------------------------------------

    @Test
    fun `GET workspaces returns a flat array of the listable tenants' workspaces`() {
        val sales = FakeWorkspace(tenantId = tenantId, name = "Sales")
        val support = FakeWorkspace(tenantId = tenantId, name = "Support")
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(sales, support)

        val app = app(grant(config.listWorkspaces, TestResources.tenant(tenantId)))

        JavalinTest.test(app) { _, http ->
            val res = http.get("/api/v1/workspaces")
            assertEquals(200, res.code)

            // A flat array, not grouped by tenant: the client groups it, by the
            // `tenant_id` on each row. (The picker's /auth/workspaces carries the
            // tenant *name* per row instead, because its rows are rendered in a
            // switcher that never reads the tenant list.)
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
    fun `GET workspaces spans every tenant the caller may list`() {
        tenants = listOf(IdTenant(tenantId, "Acme"), IdTenant(otherTenantId, "Globex"))
        every { workspaceRepository.findByTenant(tenantId) } returns
            listOf(FakeWorkspace(tenantId = tenantId, name = "Sales"))
        every { workspaceRepository.findByTenant(otherTenantId) } returns
            listOf(FakeWorkspace(tenantId = otherTenantId, name = "Compliance"))

        val app = app(
            grant(config.listWorkspaces, TestResources.tenant(tenantId)),
            grant(config.listWorkspaces, TestResources.tenant(otherTenantId)),
        )

        JavalinTest.test(app) { _, http ->
            val names = JsonParser.parseString(http.get("/api/v1/workspaces").body!!.string())
                .asJsonArray.map { it.asJsonObject.get("name").asString }

            // The screen creates into any tenant the principal may manage, so it
            // has to be able to show what it created there.
            assertEquals(listOf("Sales", "Compliance"), names)
        }
    }

    @Test
    fun `GET workspaces lists archived workspaces too`() {
        every { workspaceRepository.findByTenant(tenantId) } returns listOf(
            FakeWorkspace(tenantId = tenantId, name = "Current"),
            FakeWorkspace(tenantId = tenantId, name = "Retired", archived = true),
        )

        val app = app(grant(config.listWorkspaces, TestResources.tenant(tenantId)))

        JavalinTest.test(app) { _, http ->
            val body = JsonParser.parseString(http.get("/api/v1/workspaces").body!!.string()).asJsonArray

            // The management screen exists to show what it can act on, which
            // includes un-archiving. Filtering here would make an archived
            // workspace permanently invisible and so permanently unrecoverable.
            assertEquals(2, body.size())
            assertTrue(body.any { it.asJsonObject.get("archived").asBoolean })
        }
    }

    @Test
    fun `GET workspaces reads no tenant the caller may not list`() {
        // The gate is resolved per tenant *before* any row is read, so a tenant
        // the caller holds no `listWorkspaces` on contributes nothing — and costs
        // nothing. This is the whole of the isolation between tenants here: a row
        // from another tenant cannot reach this response, because its tenant is
        // never asked for.
        tenants = listOf(IdTenant(tenantId, "Acme"), IdTenant(otherTenantId, "Globex"))

        val app = app(grant(config.listWorkspaces, TestResources.tenant(tenantId)))

        JavalinTest.test(app) { _, http ->
            assertEquals(200, http.get("/api/v1/workspaces").code)
        }

        verify { workspaceRepository.findByTenant(tenantId) }
        verify(exactly = 0) { workspaceRepository.findByTenant(otherTenantId) }

        // The predicate runs for *every* tenant Identity reports — that is the
        // filter — and Globex's answer is no, which is why its rows are never
        // read. Both evaluations are asserted so the count stays pinned: this is
        // the one place the two halves of the grant meet.
        assertEquals(
            listOf(TestResources.tenant(tenantId), TestResources.tenant(otherTenantId)).map { it.toString() },
            evaluatedResources(config.listWorkspaces),
        )
    }

    @Test
    fun `GET workspaces does not require the workspace-scoped get action`() {
        // A management screen must be able to show a workspace in order to act
        // on it — including one the caller may not open. That is the whole
        // reason listing here is the list action alone, while the *picker* at
        // /auth/workspaces intersects the two. If this ever starts requiring
        // the get action, a principal can no longer see the workspace it needs
        // to grant itself access to.
        val app = app(grant(config.listWorkspaces, TestResources.tenant(tenantId)))

        JavalinTest.test(app) { _, http ->
            assertEquals(200, http.get("/api/v1/workspaces").code)
        }
    }

    // -- Create ----------------------------------------------------------------------

    @Test
    fun `POST workspaces returns 201 with the created workspace`() {
        val created = FakeWorkspace(tenantId = tenantId, name = "Engineering")
        every { workspaceService.createWorkspace(principalId, tenantId, "Engineering") } returns created

        val app = app(*sessionGrants())

        JavalinTest.test(app) { _, http ->
            val res = http.post(
                "/api/v1/workspaces",
                mapOf("name" to "Engineering", "tenant_id" to tenantId.toString()),
            )
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
    fun `POST workspaces creates in the tenant the request names`() {
        // The route that makes a principal's *first* workspace possible is this
        // one: it names its tenant outright, so it does not care whether the
        // session is inside a workspace — or inside this tenant at all.
        every { workspaceService.createWorkspace(principalId, otherTenantId, "Compliance") } returns
            FakeWorkspace(tenantId = otherTenantId, name = "Compliance")

        val app = app(
            *sessionGrants(grant(config.createWorkspace, TestResources.tenant(otherTenantId))),
        )

        JavalinTest.test(app) { _, http ->
            val res = http.post(
                "/api/v1/workspaces",
                mapOf("name" to "Compliance", "tenant_id" to otherTenantId.toString()),
            )

            assertEquals(201, res.code)
            assertEquals(
                otherTenantId.toString(),
                JsonParser.parseString(res.body!!.string()).asJsonObject.get("tenant_id").asString,
            )
        }

        verify { workspaceService.createWorkspace(principalId, otherTenantId, "Compliance") }
    }

    @Test
    fun `POST workspaces rejects a missing name`() {
        JavalinTest.test(app(*sessionGrants())) { _, http ->
            assertEquals(
                400,
                http.post("/api/v1/workspaces", mapOf("tenant_id" to tenantId.toString())).code,
            )
        }
        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
    }

    @Test
    fun `POST workspaces rejects a missing tenant`() {
        // No fallback to the session's selection, so there is nowhere else the
        // write could go — and the request has to say where it means. This is
        // the shape that used to answer 201 and land in whichever tenant the
        // session happened to be in.
        JavalinTest.test(app(*sessionGrants())) { _, http ->
            assertEquals(400, http.post("/api/v1/workspaces", mapOf("name" to "Sales")).code)
        }
        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
    }

    @Test
    fun `POST workspaces rejects a tenant that is not a UUID`() {
        // `UUID.fromString` throws an `IllegalArgumentException` nothing maps,
        // which Javalin answers with a 500 — so before this it was the *body*
        // that decided between a client error and a server one.
        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val res = http.post(
                "/api/v1/workspaces",
                mapOf("name" to "Sales", "tenant_id" to "not-a-uuid"),
            )
            assertEquals(400, res.code)
        }
        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
    }

    @Test
    fun `POST workspaces is gated on the config's create action at the tenant path`() {
        JavalinTest.test(app()) { _, http ->
            val res = http.post(
                "/api/v1/workspaces",
                mapOf("name" to "Nope", "tenant_id" to tenantId.toString()),
            )
            assertEquals(403, res.code)
        }

        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
        assertEquals("${TestResources.tenant(tenantId)}", evaluatedResource(config.createWorkspace))
    }

    @Test
    fun `POST workspaces gates on the tenant it writes to, not the session's`() {
        // The security-relevant one, and the shape this whole route exists to
        // fix: the caller holds `CreateWorkspace` in its own tenant and asks for
        // another. A grant in one tenant must not authorize a write into
        // another, so the gate follows the tenant being written to — and the
        // request is refused.
        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val res = http.post(
                "/api/v1/workspaces",
                mapOf("name" to "Compliance", "tenant_id" to otherTenantId.toString()),
            )
            assertEquals(403, res.code)
        }

        verify(exactly = 0) { workspaceService.createWorkspace(any(), any(), any()) }
        assertEquals(
            "${TestResources.tenant(otherTenantId)}",
            evaluatedResource(config.createWorkspace),
        )
    }

    // -- Rename ----------------------------------------------------------------------

    @Test
    fun `POST workspaces id renames and returns 204`() {
        every { workspaceRepository.findById(workspaceId) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val res = http.post("/api/v1/workspaces/$workspaceId", mapOf("name" to "Renamed"))
            assertEquals(204, res.code)
        }

        verify { workspaceService.renameWorkspace(principalId, tenantId, workspaceId, "Renamed") }
    }

    @Test
    fun `POST workspaces id passes the path id rather than the session's workspace`() {
        // The path id is the one being renamed, and for the management screen it
        // is usually *not* the workspace the caller is in. If the controller ever
        // substituted a session value here, an admin managing workspaces would
        // silently rename whichever one they happened to have selected.
        val other = UUID.randomUUID()
        every { workspaceRepository.findById(other) } returns FakeWorkspace(tenantId = tenantId)

        val app = app(
            *sessionGrants(grant(config.updateWorkspace, TestResources.tenant(tenantId, other))),
        )

        JavalinTest.test(app) { _, http ->
            assertEquals(204, http.post("/api/v1/workspaces/$other", mapOf("name" to "Renamed")).code)
        }

        verify { workspaceService.renameWorkspace(principalId, tenantId, other, "Renamed") }
    }

    @Test
    fun `POST workspaces id takes the tenant from the row being renamed`() {
        // The row is what is being written to, so it is what the write and the
        // gate both use — a workspace in a tenant the session is not in is
        // renameable by a principal holding the grant *there*, and the session's
        // own tenant is not consulted at all.
        val other = UUID.randomUUID()
        every { workspaceRepository.findById(other) } returns FakeWorkspace(tenantId = otherTenantId)

        val app = app(
            *sessionGrants(grant(config.updateWorkspace, TestResources.tenant(otherTenantId, other))),
        )

        JavalinTest.test(app) { _, http ->
            assertEquals(204, http.post("/api/v1/workspaces/$other", mapOf("name" to "Renamed")).code)
        }

        verify { workspaceService.renameWorkspace(principalId, otherTenantId, other, "Renamed") }
        assertEquals(
            "${TestResources.tenant(otherTenantId, other)}",
            evaluatedResource(config.updateWorkspace),
        )
    }

    @Test
    fun `POST workspaces id rejects a missing name`() {
        every { workspaceRepository.findById(workspaceId) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            assertEquals(400, http.post("/api/v1/workspaces/$workspaceId", mapOf<String, Any>()).code)
        }
        verify(exactly = 0) { workspaceService.renameWorkspace(any(), any(), any(), any()) }
    }

    @Test
    fun `POST workspaces id answers 404 for an id that names no row`() {
        // Read before the gate, because the row is where the tenant comes from.
        // That is the order these routes have always had in effect — the service
        // 404s an unknown id, the gate 403s a denied one — with the read moved
        // from the service to the controller so the two cannot disagree.
        JavalinTest.test(app(*sessionGrants())) { _, http ->
            assertEquals(404, http.post("/api/v1/workspaces/$workspaceId", mapOf("name" to "X")).code)
        }

        verify(exactly = 0) { workspaceService.renameWorkspace(any(), any(), any(), any()) }
        assertEquals(emptyList<String>(), evaluatedResources(config.updateWorkspace))
    }

    @Test
    fun `POST workspaces id is gated on the config's update action at the workspace path`() {
        every { workspaceRepository.findById(workspaceId) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app()) { _, http ->
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
        every { workspaceRepository.findById(workspaceId) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            assertEquals(204, http.post("/api/v1/workspaces/$workspaceId/archive", mapOf<String, Any>()).code)
        }

        verify { workspaceService.archiveWorkspace(principalId, tenantId, workspaceId) }
    }

    @Test
    fun `POST workspaces id archive takes the tenant from the row being archived`() {
        val other = UUID.randomUUID()
        every { workspaceRepository.findById(other) } returns FakeWorkspace(tenantId = otherTenantId)

        val app = app(
            *sessionGrants(grant(config.deleteWorkspace, TestResources.tenant(otherTenantId, other))),
        )

        JavalinTest.test(app) { _, http ->
            assertEquals(204, http.post("/api/v1/workspaces/$other/archive", mapOf<String, Any>()).code)
        }

        verify { workspaceService.archiveWorkspace(principalId, otherTenantId, other) }
        assertEquals(
            "${TestResources.tenant(otherTenantId, other)}",
            evaluatedResource(config.deleteWorkspace),
        )
    }

    @Test
    fun `POST workspaces id archive is gated on the config's delete action at the workspace path`() {
        every { workspaceRepository.findById(workspaceId) } returns FakeWorkspace(tenantId = tenantId)

        JavalinTest.test(app()) { _, http ->
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
        every { workspaceRepository.findById(workspaceId) } returns FakeWorkspace(tenantId = tenantId)

        val app = app(grant(config.updateWorkspace, TestResources.tenant(tenantId, workspaceId)))

        JavalinTest.test(app) { _, http ->
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

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val body = JsonParser.parseString(
                http.post(
                    "/api/v1/workspaces",
                    mapOf("name" to "Sales", "tenant_id" to tenantId.toString()),
                ).body!!.string()
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

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val keys = JsonParser.parseString(
                http.post(
                    "/api/v1/workspaces",
                    mapOf("name" to "Sales", "tenant_id" to tenantId.toString()),
                ).body!!.string()
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

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val listed = JsonParser.parseString(http.get("/api/v1/workspaces").body!!.string())
                .asJsonArray.first().asJsonObject
            val created = JsonParser.parseString(
                http.post(
                    "/api/v1/workspaces",
                    mapOf("name" to "Sales", "tenant_id" to tenantId.toString()),
                ).body!!.string()
            ).asJsonObject

            assertEquals(listed.keySet(), created.keySet())
        }
    }

    @Test
    fun `the service's returned workspace is what gets serialized`() {
        val created: IWorkspace = FakeWorkspace(tenantId = tenantId, name = "From The Service")
        every { workspaceService.createWorkspace(any(), any(), any()) } returns created

        JavalinTest.test(app(*sessionGrants())) { _, http ->
            val body = JsonParser.parseString(
                http.post(
                    "/api/v1/workspaces",
                    mapOf("name" to "Ignored", "tenant_id" to tenantId.toString()),
                ).body!!.string()
            ).asJsonObject

            assertEquals("From The Service", body.get("name").asString)
            assertEquals(created.id.toString(), body.get("id").asString)
        }
    }
}
