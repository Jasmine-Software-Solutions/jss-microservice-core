package com.jasminesoftwaresolutions.core.auth.services

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.authorization.IEvaluationService
import com.jasminesoftwaresolutions.core.auth.authorization.WorkspaceAuthentication
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IdTenant
import com.jasminesoftwaresolutions.core.domain.services.TenantDirectory
import com.jasminesoftwaresolutions.core.domain.services.TenantWorkspace
import com.jasminesoftwaresolutions.idclient.IDClient
import com.jasminesoftwaresolutions.idclient.oauth2.AuthorizationCodeFlow
import com.jasminesoftwaresolutions.idclient.oauth2.DelegatedSession
import com.jasminesoftwaresolutions.idclient.oauth2.DelegatedSessionState
import com.jasminesoftwaresolutions.idclient.session.SessionStore
import com.jasminesoftwaresolutions.witchwood.evaluation.snapshot.resources.CompiledResourcePath
import io.javalin.http.Context
import io.javalin.http.Cookie
import io.javalin.http.HandlerType
import io.javalin.http.NotFoundResponse
import io.javalin.http.SameSite
import io.javalin.http.UnauthorizedResponse
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Duration
import java.util.UUID
import java.util.logging.Logger

/**
 * Handles the OAuth2 authorization code flow between a service and ID, and the
 * workspace selection that sits on top of it.
 *
 * Delegates token lifecycle to [DelegatedSession] from id-java-client.
 * After OAuth2 exchange the [DelegatedSessionState] is stored in Redis via
 * a [SessionStore] and the browser receives only a session-ID cookie (~36 bytes).
 *
 * Token validation is performed via ID's introspect endpoint rather than
 * local JWKS verification, enabling centralized token revocation.
 *
 * **Workspaces.** A signed-in principal is not yet *in* a workspace: the
 * session cookie says who they are, and the `<namespace>_tenant` /
 * `<namespace>_workspace` cookies say where they are working. The second pair is
 * what every staff endpoint resolves its scope from — see [resolveAuthContext] —
 * and the `/auth/workspaces*` routes exist to establish it, which is why they
 * are the one surface that runs without it.
 *
 * Everything a service varies — the action strings, the resource paths, the
 * cookie namespace — arrives through [config]; nothing in this file names a
 * product.
 */
class SessionAuthService(
    private val redirectUri: String,
    private val idClient: IDClient,
    private val sessionStore: SessionStore,
    private val evaluationService: IEvaluationService,
    private val tenantDirectory: TenantDirectory,
    private val workspaceRepository: IWorkspaceRepository,
    private val config: WorkspaceAuthConfig,
) {
    private val log = Logger.getLogger(SessionAuthService::class.java.name)
    private val gson = Gson()

    /** The tenant-selection cookie for this service — see [WorkspaceAuthConfig.cookieNamespace]. */
    val tenantCookieName: String = "${config.cookieNamespace}_tenant"

    /** The workspace-selection cookie for this service — see [WorkspaceAuthConfig.cookieNamespace]. */
    val workspaceCookieName: String = "${config.cookieNamespace}_workspace"

    companion object {
        const val COOKIE_NAME = "session_token"

        /**
         * Where to send the browser after a successful sign-in.
         *
         * A cookie rather than a round-tripped query parameter, for two reasons
         * that both matter: the value has to survive a full redirect out to
         * Identity and back, and Identity will not echo an arbitrary parameter;
         * and a cookie is the only version an attacker cannot set by sending a
         * victim a link, because the link can only *ask* this server to set it,
         * and the server validates the value first.
         *
         * LAX rather than STRICT, matching `oauth2_state`: the cookie has to be
         * sent on the top-level GET navigation back from Identity, which is
         * exactly the cross-site request STRICT withholds it from.
         *
         * Not namespaced like the workspace pair: it belongs to the sign-in
         * round trip, which starts and ends on one service's own origin, so
         * there is no second reader to keep it away from.
         */
        const val RETURN_TO_COOKIE = "oauth2_return_to"

        /** Statically stored reference for `Context.requireAuth()` access. */
        @Volatile
        var instance: SessionAuthService? = null

        /**
         * A return path, or null when the value cannot be trusted as one.
         *
         * **Only a *path* is ever accepted.** The value arrives from a query
         * string an attacker can write, and it is spent as a `Location` the
         * browser follows while carrying an established session — so
         * `//evil.example` (scheme-relative), `https://evil.example` (absolute)
         * and `/\evil.example` (a backslash several browsers read as a slash)
         * are all open redirects that land on a live session.
         *
         * Validated twice on purpose — here on set, and again from the cookie on
         * read — because a cookie can be written by anything on the parent
         * domain, so the set-time check is not a guarantee about the value that
         * comes back. The second check costs a `startsWith`.
         *
         * `internal` rather than `private` so the refusals above can be tested
         * directly; an open-redirect sanitiser with no test over it is the wrong
         * trade.
         */
        internal fun sanitiseReturnTo(raw: String?): String? {
            if (raw.isNullOrEmpty()) return null
            if (!raw.startsWith("/")) return null
            if (raw.startsWith("//") || raw.startsWith("/\\")) return null
            if (raw.contains('\\') || raw.contains("://")) return null
            return raw
        }
    }

    /**
     * Tenant-scoped grants that make workspace management reachable, and so
     * decide the empty state. Both sit on `/tenant/{tenantId}` — the
     * workspace-scoped actions (`updateWorkspace`, `deleteWorkspace`) cannot
     * stand alone here, because a principal holding only those has no workspace
     * to exercise them on and nothing to show.
     */
    private val managingActions = listOf(config.listWorkspaces, config.createWorkspace)

    // -- Cookie workspace data ---------------------------------------------------------

    data class CookieWorkspaceData(val id: String, val name: String?)

    // -- OAuth2 login / callback ------------------------------------------------

    /**
     * Start the authorization-code flow.
     *
     * [scopes] is required rather than defaulted: it is the service's own grant
     * request (`forms:read forms:write …`, `hiring:read …`), the login route is
     * product-side, and a shared default would silently narrow any service that
     * forgot to pass its own.
     */
    fun redirectToAuthorize(
        ctx: Context,
        scopes: String,
        returnTo: String? = null,
    ) {
        val state = UUID.randomUUID().toString()

        ctx.cookie(Cookie("oauth2_state", state).apply {
            isHttpOnly = true; secure = true; sameSite = SameSite.LAX
            maxAge = 600
        })

        // Set unconditionally, because a stale `oauth2_return_to` from an earlier
        // sign-in must not survive into this one: a caller that passed nothing
        // means "land at the root", and leaving the old value in place would send
        // them to wherever the last one pointed. Blanking it is the cheapest way
        // to say that — the callback reads blank as absent.
        //
        // The value is a *path*, so it needs no encoding to be a legal cookie
        // value; `sanitiseReturnTo` has already refused anything with a scheme,
        // a backslash or a leading `//`. URL-encoding it here would encode the
        // `#` in a hash route, and the browser would navigate to the literal
        // `%23` and match no route at all.
        ctx.cookie(Cookie(RETURN_TO_COOKIE, sanitiseReturnTo(returnTo).orEmpty()).apply {
            isHttpOnly = true; secure = true; sameSite = SameSite.LAX
            path = "/"
            maxAge = 600
        })

        ctx.redirect(
            AuthorizationCodeFlow.buildAuthorizeUri(
                idClient.provider, idClient.id, URI(redirectUri), scopes, state
            ).toString()
        )
    }

    /**
     * Complete the authorization-code exchange and open a session.
     *
     * Returns where to send the browser — the `return_to` path this sign-in
     * started from, or `/` — or null when the callback could not be completed.
     * Null is the caller's cue to fall back (the route's own choice: the token
     * exchange failing is a sign-in that did not happen, not a server fault),
     * so nothing here throws on a failed exchange.
     */
    fun handleCallback(ctx: Context): String? {
        val code = ctx.queryParam("code") ?: return null
        val state = ctx.queryParam("state") ?: return null
        val expectedState = ctx.cookie("oauth2_state") ?: return null

        if (state != expectedState) {
            log.warning("OAuth2 callback state mismatch")
            return null
        }

        ctx.cookie(Cookie("oauth2_state", "").apply { maxAge = 0 })

        // Read and re-validate, then clear. The second `sanitiseReturnTo` is the
        // one that counts: the cookie has been out of this process's hands since
        // it was set, and anything on the parent domain can write it. Clearing
        // matters too — leaving it set would apply this sign-in's destination to
        // the next one, which is the same staleness the set-time blanking guards.
        val returnTo = sanitiseReturnTo(ctx.cookie(RETURN_TO_COOKIE))
        ctx.cookie(Cookie(RETURN_TO_COOKIE, "").apply { maxAge = 0; path = "/" })

        val delegatedSession = try {
            idClient.exchangeAuthorizationCode(URI.create(redirectUri), code).get()
        } catch (e: Exception) {
            log.severe("Token exchange failed: ${e.message}")
            return null
        }

        writeSession(ctx, delegatedSession)
        return returnTo ?: "/"
    }

    // -- Authentication ----------------------------------------------------------

    /**
     * Authenticate a request by reading the session cookie, reconstructing a
     * [DelegatedSession], and introspecting the access token against ID.
     *
     * Returns a pair of (principalId, accessToken) so controllers can use both
     * for Witchwood evaluation and ID API proxying.
     *
     * Logs only token *prefixes* and the presence of the cookie — never the
     * session id or the tokens themselves: this runs on every request and its
     * output lands in whatever collects stdout.
     *
     * @throws UnauthorizedResponse if no cookie, invalid cookie, or invalid token.
     */
    fun authenticateFromCookie(ctx: Context): Pair<UUID, String> {
        val callId = System.nanoTime()
        val cookieVal = ctx.cookie(COOKIE_NAME)
        System.out.printf("[session:%d] ENTER  thread=%s cookie=%s%n",
            callId, Thread.currentThread().getName(),
            if (cookieVal == null) "null" else "present")

        val session = restoreSession(ctx)
            ?: throw UnauthorizedResponse("No session cookie")

        val cachedRt = session.cachedToken?.refreshToken?.take(12) ?: "null"
        System.out.printf("[session:%d] RESTORE thread=%s rtPrefix=%s → calling getAccessToken%n",
            callId, Thread.currentThread().getName(), cachedRt)

        val accessToken = try {
            session.getAccessToken().get()
        } catch (e: Exception) {
            System.out.printf("[session:%d] FAIL thread=%s getAccessToken threw: %s%n",
                callId, Thread.currentThread().getName(), e.message)
            removeSession(ctx)
            throw UnauthorizedResponse("Session expired — please log in again")
        }

        System.out.printf("[session:%d] TOKEN thread=%s atPrefix=%s → introspecting%n",
            callId, Thread.currentThread().getName(), accessToken.take(12))

        return try {
            val introspection = idClient.introspectToken(accessToken).get()

            if (!introspection.isActive) {
                System.out.printf("[session:%d] INTROSPECT_INACTIVE thread=%s atPrefix=%s%n",
                    callId, Thread.currentThread().getName(), accessToken.take(12))
                throw UnauthorizedResponse("Token is not active")
            }

            val sub = introspection.sub
                ?: throw UnauthorizedResponse("Introspection response missing sub claim")

            val principalId = UUID.fromString(sub)
            System.out.printf("[session:%d] OK thread=%s principalId=%s%n",
                callId, Thread.currentThread().getName(), principalId)
            principalId to accessToken
        } catch (e: UnauthorizedResponse) {
            throw e
        } catch (e: Exception) {
            System.out.printf("[session:%d] INTROSPECT_FAIL thread=%s err=%s%n",
                callId, Thread.currentThread().getName(), e.message)
            removeSession(ctx)
            throw UnauthorizedResponse("Token introspection failed: ${e.message}")
        }
    }

    /**
     * Authenticate a server-to-server request by reading a Bearer token from
     * the Authorization header and introspecting it against ID.
     *
     * Returns a pair of (principalId, accessToken) so controllers can use both
     * for Witchwood evaluation and ID API proxying.
     *
     * @throws UnauthorizedResponse if no Authorization header, invalid header,
     *         failed introspection, or inactive token.
     */
    fun authenticateFromBearer(ctx: Context): Pair<UUID, String> {
        val authHeader = ctx.header("Authorization")
            ?: throw UnauthorizedResponse("No Authorization header")

        if (!authHeader.startsWith("Bearer ", ignoreCase = true))
            throw UnauthorizedResponse("Invalid Authorization header")

        val token = authHeader.substring(7).trim()

        val introspection = try {
            idClient.introspectToken(token).get()
        } catch (e: Exception) {
            throw UnauthorizedResponse("Token introspection failed: ${e.message}")
        }

        if (!introspection.isActive)
            throw UnauthorizedResponse("Token is not active")

        val sub = introspection.sub
            ?: throw UnauthorizedResponse("Introspection response missing sub claim")

        val principalId = UUID.fromString(sub)
        return principalId to token
    }

    // -- Workspace context ---------------------------------------------------

    /**
     * Persist the selected workspace in httpOnly cookies so the browser
     * client doesn't need to send tenant_id/workspace_id query params.
     */
    fun setWorkspace(
        ctx: Context,
        tenantId: String,
        tenantName: String?,
        workspaceId: String,
        workspaceName: String?,
    ) {
        val tenantData = URLEncoder.encode(gson.toJson(CookieWorkspaceData(tenantId, tenantName)), "UTF-8")
        val workspaceData = URLEncoder.encode(gson.toJson(CookieWorkspaceData(workspaceId, workspaceName)), "UTF-8")

        ctx.cookie(Cookie(tenantCookieName, tenantData).apply {
            isHttpOnly = true; secure = true; path = "/"; sameSite = SameSite.STRICT
        })
        ctx.cookie(Cookie(workspaceCookieName, workspaceData).apply {
            isHttpOnly = true; secure = true; path = "/"; sameSite = SameSite.STRICT
        })
    }

    /** Read a JSON-encoded workspace cookie. Returns null if missing or malformed. */
    fun readWorkspaceCookie(ctx: Context, name: String): CookieWorkspaceData? {
        val raw = ctx.cookie(name) ?: return null
        return try {
            val decoded = URLDecoder.decode(raw, "UTF-8")
            gson.fromJson(decoded, CookieWorkspaceData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The tenants the principal can *manage* workspaces in — it holds
     * `listWorkspaces` or `createWorkspace` there. Feeds both the management
     * view's tenant picker and the empty-state decision.
     *
     * Filtered rather than returning every tenant ID reports. The empty state
     * has two outcomes — workspace management, or a 404 for a principal that
     * cannot reach it — and the two grants below are exactly that predicate, so
     * an unfiltered list would both hand tenant names to a principal with no
     * grant and leave the frontend unable to decide. Filtering here is what
     * makes "non-empty" mean "there is a management screen to land on".
     */
    fun resolveManageableTenants(principalId: UUID, accessToken: String): List<IdTenant> =
        tenantDirectory.listTenants(accessToken).filter { tenant ->
            managingActions.any { evaluate(principalId, it, config.tenantPath(tenant.id, null)) }
        }

    /**
     * The workspaces this principal may both *see* and *open* — the picker's set.
     *
     * Visibility is the intersection of two independent grants sitting on two
     * different resource paths:
     *
     *   listWorkspaces → /tenant/{tenantId}                          (tenant-scoped)
     *   getWorkspace   → /tenant/{tenantId}/workspace/{workspaceId}  (workspace-scoped)
     *
     * Resource paths match at **exact length**, so a grant at `/tenant/X` does
     * not cover `/tenant/X/workspace/Y` and the two are genuinely independent.
     * Three behaviours fall out of the intersection for free:
     *
     *  - `listWorkspaces` alone lists nothing, because no workspace clears the
     *    second half. (Such a principal still reaches the management view, which
     *    is gated on `listWorkspaces`/`createWorkspace` on the tenant rather
     *    than on this list.)
     *  - `getWorkspace` on a workspace without `listWorkspaces` leaves it
     *    reachable through the API but absent here — the deliberate API-only case.
     *  - Denying `getWorkspace` on one workspace hides exactly that one, even
     *    though the tenant-wide grant is present. Hiding needs no explicit deny
     *    signal beyond the absent ALLOW.
     *
     * The `listWorkspaces` half does not depend on the workspace, so it is
     * hoisted to a single evaluation per tenant.
     *
     * **Archived workspaces are never listed**, whatever the grants say. This is
     * a filter on top of the intersection, not a third grant: the set is what the
     * picker *offers to open*, and an archived workspace is closed. The
     * management view is deliberately the opposite —
     * `WorkspaceController.listWorkspaces` reads the repository directly and
     * lists archived rows, because that is where an archived workspace stays
     * visible so it can be acted on.
     *
     * This cannot be answered by `queryResources`: that returns stored ALLOW
     * *patterns* verbatim and does not expand a tenant-level allow into
     * workspace paths, so it is a hint at best and never an authorization
     * decision.
     *
     * ID is consulted only for *tenants*. Workspaces are this service's own
     * rows, read from its own database.
     */
    fun resolveListableWorkspaces(principalId: UUID, accessToken: String): List<TenantWorkspace> {
        val result = mutableListOf<TenantWorkspace>()

        for (tenant in tenantDirectory.listTenants(accessToken)) {
            if (!evaluate(principalId, config.listWorkspaces, config.tenantPath(tenant.id, null))) continue

            for (workspace in workspaceRepository.findByTenant(tenant.id)) {
                // Closed places are not offered, however the grants read. Checked
                // before the evaluation so an archived row costs nothing to skip.
                if (workspace.archived) continue

                if (!evaluate(principalId, config.getWorkspace, config.tenantPath(workspace.tenantId, workspace.id))) continue

                result += TenantWorkspace(
                    tenantId = tenant.id,
                    tenantName = tenant.name,
                    workspaceId = workspace.id,
                    workspaceName = workspace.name,
                )
            }
        }

        return result
    }

    /**
     * Evaluate one action for a principal that has no workspace context yet.
     *
     * Needed by the bootstrap routes, which by definition run before a workspace
     * is in scope and so cannot go through [AuthContext.evaluate].
     */
    fun evaluate(principalId: UUID, action: String, resource: CompiledResourcePath): Boolean =
        evaluationService.evaluate(principalId, action, resource, emptyMap()).get()

    // -- AuthContext (dual-mode) -------------------------------------------------

    /**
     * Create an [AuthContext] for the current request.
     *
     * If `tenant_id` and `workspace_id` are provided (from query params or POST
     * body), they are used directly. Otherwise, the values are read from the
     * `<namespace>_tenant` and `<namespace>_workspace` cookies.
     *
     * The before-filter has already authenticated every request that reaches
     * here and left the pair it resolved on the context, so this reuses that
     * rather than introspecting a second time; the routes that are exempt from
     * the filter (the `/auth/` bootstrap path) authenticate from here instead.
     *
     * @throws UnauthorizedResponse when no workspace is selected, and
     *         [NotFoundResponse] when the principal has no grant on the one it
     *         named.
     */
    fun resolveAuthContext(ctx: Context): AuthContext {
        val authenticated = ctx.attribute<UUID>("principalId")
            ?.let { principal -> ctx.attribute<String>("accessToken")?.let { principal to it } }
            ?: when {
                ctx.header("Authorization")?.startsWith("Bearer ", ignoreCase = true) == true ->
                    authenticateFromBearer(ctx)
                else ->
                    authenticateFromCookie(ctx)
            }
        val (principalId, accessToken) = authenticated

        var resolvedTenantId: UUID? = null
        var resolvedWorkspaceId: UUID? = null

        if (ctx.method() == HandlerType.GET) {
            resolvedTenantId = ctx.queryParam("tenant_id")?.let(UUID::fromString)
            resolvedWorkspaceId = ctx.queryParam("workspace_id")?.let(UUID::fromString)
        } else {
            try {
                val body = JsonParser.parseString(ctx.body())?.asJsonObject
                resolvedTenantId = body?.get("tenant_id")?.asString?.let(UUID::fromString)
                resolvedWorkspaceId = body?.get("workspace_id")?.asString?.let(UUID::fromString)
            } catch (_: Exception) {
            }
        }

        if (resolvedTenantId == null || resolvedWorkspaceId == null) {
            val tenant = readWorkspaceCookie(ctx, tenantCookieName)
            val workspace = readWorkspaceCookie(ctx, workspaceCookieName)

            if (tenant == null || workspace == null)
                throw UnauthorizedResponse("No workspace selected")

            resolvedTenantId = UUID.fromString(tenant.id)
            resolvedWorkspaceId = UUID.fromString(workspace.id)
        }

        // Opening a workspace requires GET_WORKSPACE on the pair actually being
        // claimed. Until this check existed, whichever UUIDs the query params,
        // POST body or cookie happened to carry were trusted outright — being
        // authenticated was sufficient to address any workspace.
        //
        // 404, not 403: a principal with no grant on a workspace should not be
        // able to tell "exists but forbidden" from "does not exist".
        if (!evaluate(principalId, config.getWorkspace, config.tenantPath(resolvedTenantId, resolvedWorkspaceId)))
            throw NotFoundResponse("Workspace not found")

        return AuthContext(principalId, resolvedTenantId, resolvedWorkspaceId, accessToken)
    }

    // -- AuthContext inner class -------------------------------------------------

    inner class AuthContext(
        override val principalId: UUID,
        override val tenantId: UUID,
        override val workspaceId: UUID,
        override val accessToken: String
    ) : WorkspaceAuthentication {
        override val workspaces by lazy {
            resolveListableWorkspaces(principalId, accessToken)
        }

        override fun evaluate(action: String, resource: CompiledResourcePath): Boolean =
            evaluationService.evaluate(principalId, action, resource, emptyMap()).get()

        override fun queryResources(action: String): Set<CompiledResourcePath> =
            evaluationService.queryResources(principalId, action).get()
    }

    // -- Session storage ---------------------------------------------------------

    private fun restoreSession(ctx: Context): DelegatedSession? {
        val sessionId = ctx.cookie(COOKIE_NAME) ?: return null
        val state = try {
            sessionStore.get(sessionId).get()
        } catch (_: Exception) {
            return null
        } ?: return null

        System.out.printf("[session:restore] thread=%s sessionId=%s atPrefix=%s rtPrefix=%s expiresIn=%d%n",
            Thread.currentThread().getName(),
            sessionId.take(8),
            state.accessToken?.take(12) ?: "null",
            state.refreshToken?.take(12) ?: "null",
            state.expiresIn)

        val session = idClient.restoreDelegatedSession(state)

        session.setOnRefreshed { s ->
            val updatedState = s.toState()
            val ttl = updatedState.refreshTokenExpiresIn?.let { Duration.ofSeconds(it) }
                ?: Duration.ofHours(3)
            System.out.printf("[session:onRefreshed] thread=%s newRtPrefix=%s newAtPrefix=%s → saving to Redis%n",
                Thread.currentThread().getName(),
                s.cachedToken?.refreshToken?.take(12) ?: "null",
                s.cachedToken?.accessToken?.take(12) ?: "null")
            sessionStore.save(sessionId, updatedState, ttl)
        }

        return session
    }

    fun writeSession(ctx: Context, session: DelegatedSession) {
        val sessionId = UUID.randomUUID().toString()
        val state = session.toState()
        val ttl = state.refreshTokenExpiresIn?.let { Duration.ofSeconds(it) }
            ?: Duration.ofHours(3)

        sessionStore.save(sessionId, state, ttl)

        session.setOnRefreshed { s ->
            val updatedState = s.toState()
            val updatedTtl = updatedState.refreshTokenExpiresIn?.let { Duration.ofSeconds(it) }
                ?: Duration.ofHours(3)
            sessionStore.save(sessionId, updatedState, updatedTtl)
        }

        ctx.cookie(Cookie(COOKIE_NAME, sessionId).apply {
            isHttpOnly = true
            secure = true
            sameSite = SameSite.STRICT
        })
    }

    // -- Logout ------------------------------------------------------------------

    /**
     * Removes the session from Redis and clears the browser cookies.
     *
     * The workspace pair goes too. It is not a credential and clearing it is not
     * what ends the session, but leaving it behind would let a *different*
     * principal signing in on the same browser arrive already inside the
     * previous one's workspace — the cookie would resolve, and the selection
     * screen would never appear.
     */
    fun logout(ctx: Context) {
        val sessionId = ctx.cookie(COOKIE_NAME)
        if (sessionId != null) {
            sessionStore.remove(sessionId)
        }
        ctx.cookie(Cookie(COOKIE_NAME, "").apply { maxAge = 0; path = "/" })
        ctx.cookie(Cookie("oauth2_state", "").apply { maxAge = 0; path = "/" })
        ctx.cookie(Cookie(RETURN_TO_COOKIE, "").apply { maxAge = 0; path = "/" })
        ctx.cookie(Cookie(tenantCookieName, "").apply { maxAge = 0; path = "/" })
        ctx.cookie(Cookie(workspaceCookieName, "").apply { maxAge = 0; path = "/" })
    }

    private fun removeSession(ctx: Context) {
        val sessionId = ctx.cookie(COOKIE_NAME)
        if (sessionId != null) {
            sessionStore.remove(sessionId)
        }
        ctx.removeCookie(COOKIE_NAME)
    }
}
