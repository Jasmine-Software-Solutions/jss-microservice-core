package com.jasminesoftwaresolutions.core.auth.services

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jasminesoftwaresolutions.core.auth.authorization.IEvaluationService
import com.jasminesoftwaresolutions.core.auth.testutil.testAuthConfig
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IdTenant
import com.jasminesoftwaresolutions.core.domain.services.TenantDirectory
import com.jasminesoftwaresolutions.core.interfaces.GsonJsonMapper
import com.jasminesoftwaresolutions.core.server.infrastructure.util.GsonFactory
import com.jasminesoftwaresolutions.idclient.IDClient
import com.jasminesoftwaresolutions.idclient.core.IDProvider
import com.jasminesoftwaresolutions.idclient.session.SessionStore
import io.javalin.Javalin
import io.javalin.testtools.JavalinTest
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * The workspace-selection cookies: what `setWorkspace` writes, what
 * `readWorkspaceCookie` reads back, and what `logout` clears.
 *
 * This is the wire between the picker and every staff endpoint — the endpoints
 * resolve their scope from these two cookies and nothing else — so the shape
 * matters beyond aesthetics:
 *
 *  - **httpOnly** is the load-bearing flag. The frontend switcher had to start
 *    *awaiting* its selection call because the response only lands once the
 *    server has written these cookies; a client-side mirror in `localStorage`
 *    is a convenience, not the source of truth.
 *  - **The JSON payload** is what `readWorkspaceCookie` parses and what the
 *    management page renders from. It is URL-encoded on the way in, because a
 *    raw JSON cookie value is not a legal token and browsers trim it at the
 *    first space — the failure looks like "the workspace never persists".
 *  - **The clear on logout** is what stops the next principal on the same
 *    browser from arriving already inside the previous one's workspace.
 *  - **The names are namespaced** (`<namespace>_tenant` / `_workspace`), which
 *    is why this test reads them off the instance rather than naming them: two
 *    services sharing a parent domain must not read each other's selection.
 *
 * The cookies are set over real HTTP and read back from the `Set-Cookie`
 * headers rather than through the test client's cookie jar: the jar is the
 * browser's behaviour, and these cookies are marked `Secure`, which a jar is
 * entitled to withhold over plain http. Reading the header asserts the contract
 * at the wire, which is where the browser picks it up.
 */
class SessionAuthServiceCookieTest {

    private val gson = Gson()
    private val wireGson = GsonFactory.create()

    private val sessionStore = mockk<SessionStore>(relaxed = true)
    private val workspaceRepository = mockk<IWorkspaceRepository>(relaxed = true)
    private val evaluationService = mockk<IEvaluationService>(relaxed = true)

    private val tenantId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun sessionAuth() = SessionAuthService(
        redirectUri = "http://localhost/auth/callback",
        idClient = IDClient(IDProvider(URI("http://localhost")), UUID.randomUUID(), "secret"),
        sessionStore = sessionStore,
        evaluationService = evaluationService,
        tenantDirectory = object : TenantDirectory {
            override fun listTenants(accessToken: String): List<IdTenant> = emptyList()
        },
        workspaceRepository = workspaceRepository,
        config = testAuthConfig(),
    )

    /** Two stand-in endpoints: one writes the selection, one reports what it reads. */
    private fun app(auth: SessionAuthService): Javalin =
        Javalin.create { config -> config.jsonMapper(GsonJsonMapper(wireGson)) }.apply {
            post("/set") { ctx ->
                auth.setWorkspace(ctx, tenantId.toString(), "Acme", workspaceId.toString(), "Sales")
                ctx.status(204)
            }
            get("/read") { ctx ->
                val workspace = auth.readWorkspaceCookie(ctx, auth.workspaceCookieName)
                if (workspace == null) {
                    ctx.status(404)
                } else {
                    val tenant = auth.readWorkspaceCookie(ctx, auth.tenantCookieName)
                    ctx.json(mapOf("tenant" to tenant, "workspace" to workspace))
                }
            }
            post("/logout") { ctx ->
                auth.logout(ctx)
                ctx.status(204)
            }
        }

    private fun setCookieValue(header: String): String =
        header.substringAfter('=').substringBefore(';')

    /** The `Set-Cookie` headers for one cookie name, as written. */
    private fun headersNamed(res: okhttp3.Response, name: String): List<String> =
        res.headers("Set-Cookie").filter { it.startsWith("$name=") }

    @Test
    fun `setWorkspace writes both cookies httpOnly, Secure and Strict`() {
        val auth = sessionAuth()
        JavalinTest.test(app(auth)) { _, http ->
            val res = http.post("/set", mapOf<String, Any>())
            assertEquals(204, res.code)

            val headers = res.headers("Set-Cookie")

            for (name in listOf(auth.tenantCookieName, auth.workspaceCookieName)) {
                val header = headers.singleOrNull { it.startsWith("$name=") }
                assertNotNull(header, "$name was not set")

                // httpOnly: the token-of-record is server-side, and nothing in the
                // frontend reads these directly — the switcher keeps its own copy
                // in localStorage for rendering only.
                assertTrue(header!!.contains("HttpOnly"), "$name must be HttpOnly: $header")
                assertTrue(header.contains("Secure"), "$name must be Secure: $header")
                assertTrue(header.contains("SameSite=Strict"), "$name must be SameSite=Strict: $header")
                assertTrue(header.contains("Path=/"), "$name must be readable on every route: $header")
            }
        }
    }

    @Test
    fun `the cookie names carry the service's namespace`() {
        val auth = sessionAuth()

        // The namespace is the only thing keeping two services on one parent
        // domain from reading each other's selection, and a collision resolves a
        // workspace id that does not exist in the reading service's database.
        assertEquals("test_tenant", auth.tenantCookieName)
        assertEquals("test_workspace", auth.workspaceCookieName)
    }

    @Test
    fun `the cookie round-trips the workspace and tenant it was set with`() {
        val auth = sessionAuth()
        JavalinTest.test(app(auth)) { _, http ->
            val set = http.post("/set", mapOf<String, Any>())

            // The values as the browser would send them back: taken straight off
            // the Set-Cookie headers, so what is asserted below is the full
            // encode-on-write / decode-on-read pair rather than two calls to the
            // same encoder.
            val sent = listOf(auth.tenantCookieName, auth.workspaceCookieName)
                .joinToString("; ") { name ->
                    "$name=" + setCookieValue(headersNamed(set, name).single())
                }

            val res = http.get("/read") { it.header("Cookie", sent) }
            assertEquals(200, res.code)

            val body = JsonParser.parseString(res.body!!.string()).asJsonObject
            assertEquals(workspaceId.toString(), body.getAsJsonObject("workspace").get("id").asString)
            assertEquals("Sales", body.getAsJsonObject("workspace").get("name").asString)
            assertEquals(tenantId.toString(), body.getAsJsonObject("tenant").get("id").asString)
            assertEquals("Acme", body.getAsJsonObject("tenant").get("name").asString)
        }
    }

    @Test
    fun `the cookie payload is URL-encoded JSON`() {
        val auth = sessionAuth()
        JavalinTest.test(app(auth)) { _, http ->
            val res = http.post("/set", mapOf<String, Any>())
            val raw = setCookieValue(headersNamed(res, auth.workspaceCookieName).single())

            // A raw JSON value is not a legal cookie: the `{`, the quotes and the
            // space after the colon all have to survive, and unencoded the
            // browser truncates at the first space — which reads as "the
            // workspace never persists" rather than as an encoding bug.
            val decoded = gson.fromJson(URLDecoder.decode(raw, "UTF-8"), Map::class.java)
            assertEquals(workspaceId.toString(), decoded["id"])
            assertEquals("Sales", decoded["name"])
        }
    }

    @Test
    fun `a malformed cookie reads as absent rather than throwing`() {
        val auth = sessionAuth()
        JavalinTest.test(app(auth)) { _, http ->
            // The cookie is attacker-writable by anything on the parent domain,
            // so a parse failure has to be a 404 (nothing selected), not a 500.
            val res = http.get("/read") {
                it.header("Cookie", "${auth.workspaceCookieName}=not-json")
            }

            assertEquals(404, res.code)
        }
    }

    @Test
    fun `logout clears both workspace cookies`() {
        val auth = sessionAuth()
        JavalinTest.test(app(auth)) { _, http ->
            val res = http.post("/logout", mapOf<String, Any>())
            assertEquals(204, res.code)

            for (name in listOf(auth.tenantCookieName, auth.workspaceCookieName)) {
                val header = headersNamed(res, name).single()
                // Clearing is what stops the next principal on this browser from
                // arriving inside the previous one's workspace, with the
                // selection screen never appearing.
                assertTrue(header.contains("Max-Age=0"), "$name must be cleared: $header")
            }
        }
    }
}
