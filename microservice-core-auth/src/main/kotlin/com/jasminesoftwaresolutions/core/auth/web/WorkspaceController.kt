package com.jasminesoftwaresolutions.core.auth.web

import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.services.SessionAuthService
import com.jasminesoftwaresolutions.core.domain.models.IWorkspace
import com.jasminesoftwaresolutions.core.domain.repositories.IWorkspaceRepository
import com.jasminesoftwaresolutions.core.domain.services.IWorkspaceService
import io.javalin.community.routing.annotations.Endpoints
import io.javalin.community.routing.annotations.Get
import io.javalin.community.routing.annotations.Param
import io.javalin.community.routing.annotations.Post
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.NotFoundResponse
import java.util.UUID

/**
 * Every workspace route this service serves — all seven of them.
 *
 * **One controller, one authentication posture.** Each handler opens by calling
 * [SessionAuthService.authenticate] and then evaluates the one action its route
 * writes to, at the path that write touches. Nothing here calls `requireAuth()`,
 * and nothing resolves a *selected workspace*: the tenant is always named by the
 * request or read from the row being written, never inferred from a scope the
 * caller happens to be standing in.
 *
 * That is what let the former pair of controllers — `WorkspaceBootstrapController`
 * under `/api/v1/auth/`, `WorkspaceController` under `/api/v1/` — be one class
 * again. They were split by whether a workspace was selected, and the split was
 * load-bearing only for as long as the management routes took their tenant from
 * `auth.tenantId`: a route that must run before a selection cannot do that. With
 * the tenant named explicitly on every route, none of them needs a selection, so
 * the distinction had nothing left to distinguish — and while it lasted it hid a
 * real divergence, two creates with different tenant semantics in two files.
 *
 * The two creates are now [createWorkspace] alone, with the tenant **required**
 * and the gate evaluated on it. The bootstrap sibling it replaces
 * (`POST /api/v1/auth/workspaces`, 204, no body back) was reachable before a
 * selection, which is the one thing this route could not do while the tenant
 * came from the session — and stopped being a reason the moment it did not.
 * Two URLs for one write is what let the pair drift apart; there is one now.
 *
 * The gates below evaluate [WorkspaceAuthConfig]'s actions and paths — product
 * policy data injected at construction, never hardcoded here.
 */
@Endpoints("/api/v1")
class WorkspaceController(
    private val sessionAuthService: SessionAuthService,
    private val workspaceService: IWorkspaceService,
    private val workspaceRepository: IWorkspaceRepository,
    private val config: WorkspaceAuthConfig,
) {
    // -- Request / response DTOs ------------------------------------------------

    /**
     * The create body. **`tenant_id` is required** — there is no session
     * fallback, so nothing is ever created in a tenant the request did not name.
     *
     * A class of its own rather than a field added to [WorkspaceNameRequest]:
     * that one is the *rename* body, and widening it would silently widen the
     * rename too. The two were one class, which is exactly how the create came
     * to drop a `tenant_id` — and answer `201` — without saying so.
     */
    data class CreateWorkspaceRequest(val name: String?, val tenantId: String?)

    /** The rename body — see [CreateWorkspaceRequest] for why the two are not one class. */
    data class WorkspaceNameRequest(val name: String?)

    data class SelectWorkspaceRequest(
        val tenantId: String,
        val tenantName: String?,
        val workspaceId: String,
        val workspaceName: String?,
    )

    data class WorkspaceResponse(
        val id: UUID,
        val tenantId: UUID,
        val name: String,
        val archived: Boolean,
        val createdAt: Long,
    )

    data class TenantWorkspaceResponse(
        val tenantId: UUID,
        val tenantName: String,
        val workspaceId: UUID,
        val workspaceName: String,
    )

    data class TenantResponse(
        val tenantId: UUID,
        val tenantName: String,
    )

    // -- Reachable before a workspace is selected -------------------------------

    /**
     * The workspaces this principal may see *and* open — the picker's set, and an
     * empty array is a valid answer. Archived workspaces are excluded: the picker
     * offers places to open, and an archived workspace is closed. They remain in
     * the management view ([listWorkspaces]), which lists archived rows so they
     * can be acted on.
     *
     * An empty list is not an error and not a dead end. The frontend pairs it
     * with [listTenants] to decide between the workspace-management empty state
     * (when the principal can actually create one) and a 404 (when it cannot).
     * Only the frontend can make that call, because only it knows whether a
     * management screen is reachable.
     */
    @Get("/auth/workspaces")
    fun bootstrapListWorkspaces(ctx: Context) {
        val (principalId, accessToken) = sessionAuthService.authenticate(ctx)

        ctx.json(
            sessionAuthService.resolveListableWorkspaces(principalId, accessToken).map {
                TenantWorkspaceResponse(it.tenantId, it.tenantName, it.workspaceId, it.workspaceName)
            }
        )
    }

    /**
     * Persist the selection in cookies.
     *
     * Gated on `getWorkspace` for the target: selecting is how a principal
     * *opens* a workspace, so it carries the same requirement as addressing one.
     * 404 rather than 403, matching `resolveAuthContext` — a principal with no
     * grant should not learn that the workspace exists.
     */
    @Post("/auth/workspaces/select")
    fun selectWorkspace(ctx: Context) {
        val (principalId, _) = sessionAuthService.authenticate(ctx)
        val body = ctx.bodyAsClass(SelectWorkspaceRequest::class.java)
        val tenantId = UUID.fromString(body.tenantId)
        val workspaceId = UUID.fromString(body.workspaceId)

        if (!sessionAuthService.evaluate(principalId, config.getWorkspace, config.tenantPath(tenantId, workspaceId)))
            throw NotFoundResponse("Workspace not found")

        sessionAuthService.setWorkspace(
            ctx, body.tenantId, body.tenantName,
            body.workspaceId, body.workspaceName,
        )
        ctx.status(204)
    }

    /**
     * The tenants the principal can *manage* workspaces in, and the only
     * evidence that separates the two empty states.
     *
     * A principal with no listable workspace gets workspace management when this
     * is non-empty and a 404 when it is not. Nothing else distinguishes them:
     * the workspace list is empty in both cases, and the grant that would tell
     * them apart is not otherwise exposed. See
     * [SessionAuthService.resolveManageableTenants].
     *
     * This is also the create dialog's tenant picker — the same set, and for the
     * same reason: it is exactly the tenants the principal may write a workspace
     * into.
     */
    @Get("/auth/tenants")
    fun listTenants(ctx: Context) {
        val (principalId, accessToken) = sessionAuthService.authenticate(ctx)
        ctx.json(
            sessionAuthService.resolveManageableTenants(principalId, accessToken)
                .map { TenantResponse(it.id, it.name) }
        )
    }

    // -- Management, which needs no selection either ----------------------------

    /**
     * Every workspace in every tenant the caller may **list**, archived included
     * — the management view's list.
     *
     * The set spans tenants, and that is the point: the screen creates into any
     * tenant the caller may manage, so it has to be able to show what it
     * created. The tenants come from
     * [SessionAuthService.resolveListableTenants] — one `listWorkspaces`
     * evaluation each — and the rows are this service's own. The `tenant_id` on
     * each row is what the client groups by.
     *
     * Gated on `listWorkspaces` per tenant, rather than on `getWorkspace` per
     * row. That is deliberate and differs from the picker at
     * [bootstrapListWorkspaces], which intersects the two: a management screen
     * has to be able to show a workspace in order to act on it, including one
     * the caller could not open.
     *
     * A tenant the caller may not list contributes no rows and no error — the
     * set is filtered server-side, exactly as the picker's is.
     */
    @Get("/workspaces")
    fun listWorkspaces(ctx: Context) {
        val (principalId, accessToken) = sessionAuthService.authenticate(ctx)

        val rows = sessionAuthService.resolveListableTenants(principalId, accessToken)
            .flatMap { workspaceRepository.findByTenant(it.id) }

        ctx.json(rows.map { it.toResponse() })
    }

    /**
     * Create a workspace in a tenant named by the request, and answer **201 with
     * the row** so a client need not re-list to learn its id.
     *
     * **Works before a workspace is selected**, which is why there is only one
     * of these: a principal creating its first workspace is doing the same thing
     * as one creating its tenth, and the tenant it is creating in has to be
     * named either way. There is no fallback to the session's selection, so a
     * create can never land anywhere but the tenant it asked for.
     *
     * `createWorkspace` is evaluated against `/tenant/{tenantId}` rather than a
     * workspace path because the workspace does not exist yet, which is why the
     * config's `tenantPath` takes a nullable workspace segment. The gate follows
     * **the tenant being written to**: a grant in one tenant must not authorize
     * a write into another.
     */
    @Post("/workspaces")
    fun createWorkspace(ctx: Context) {
        val (principalId, _) = sessionAuthService.authenticate(ctx)
        val body = ctx.bodyAsClass(CreateWorkspaceRequest::class.java)

        val created = create(
            principalId,
            parseTenantId(body.tenantId),
            body.name ?: throw BadRequestResponse("Missing name"),
        )
        ctx.status(201).json(created.toResponse())
    }

    /**
     * Rename a workspace, addressed by id.
     *
     * The tenant comes from the **row**, not from the session: the row is what is
     * being written to, and evaluating the grant against whatever tenant the
     * caller happens to be standing in would let a grant in one tenant authorize
     * a write into another. It also drops the precondition the session-derived
     * version carried — that the caller's *own* selection clear `getWorkspace` —
     * which had nothing to do with renaming this one.
     *
     * *Known and unchanged:* an id that names no row answers 404 and one the
     * caller may not write answers 403, which tells the two apart. That is the
     * opposite of the 404-not-403 discipline `resolveAuthContext` and
     * [selectWorkspace] follow, and it is what these routes have always done —
     * the service 404s a foreign id and the gate 403s. It is not a new leak, and
     * it is not worth a client contract change: the routes are id-addressed, so a
     * client only ever holds ids it has been shown.
     */
    @Post("/workspaces/{workspaceId}")
    fun renameWorkspace(ctx: Context, @Param("workspaceId") workspaceId: UUID) {
        val (principalId, _) = sessionAuthService.authenticate(ctx)
        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw NotFoundResponse("Workspace not found")

        if (!sessionAuthService.evaluate(
                principalId, config.updateWorkspace, config.tenantPath(workspace.tenantId, workspaceId)
            )
        )
            throw ForbiddenResponse()

        val body = ctx.bodyAsClass(WorkspaceNameRequest::class.java)
        workspaceService.renameWorkspace(
            principalId, workspace.tenantId, workspaceId,
            body.name ?: throw BadRequestResponse("Missing name"),
        )
        ctx.status(204)
    }

    /** Archive a workspace, addressed by id — the tenant, and the gate, come from the row. See [renameWorkspace]. */
    @Post("/workspaces/{workspaceId}/archive")
    fun archiveWorkspace(ctx: Context, @Param("workspaceId") workspaceId: UUID) {
        val (principalId, _) = sessionAuthService.authenticate(ctx)
        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw NotFoundResponse("Workspace not found")

        if (!sessionAuthService.evaluate(
                principalId, config.deleteWorkspace, config.tenantPath(workspace.tenantId, workspaceId)
            )
        )
            throw ForbiddenResponse()

        workspaceService.archiveWorkspace(principalId, workspace.tenantId, workspaceId)
        ctx.status(204)
    }

    // -- The create, gate and write together ------------------------------------

    /**
     * The gate and the write, together, so a future second create route cannot
     * evaluate one thing and write another. `createWorkspace` is always
     * evaluated against **exactly the tenant passed here** — the one the write
     * will use.
     */
    private fun create(principalId: UUID, tenantId: UUID, name: String): IWorkspace {
        if (!sessionAuthService.evaluate(principalId, config.createWorkspace, config.tenantPath(tenantId, null)))
            throw ForbiddenResponse()

        return workspaceService.createWorkspace(principalId, tenantId, name)
    }

    /**
     * The tenant id the request names, or the 400 saying what was wrong with it.
     *
     * `UUID.fromString` throws an `IllegalArgumentException` that nothing maps,
     * and Javalin answers an unmapped exception with a 500 — so a body carrying
     * `"not-a-uuid"`, or none at all, used to read as a server fault rather than
     * as the client error it is. The two cases are told apart because a client
     * fixes them differently: one is a field it forgot, the other a value it
     * spelled wrong.
     */
    private fun parseTenantId(raw: String?): UUID {
        if (raw == null) throw BadRequestResponse("Missing tenantId")

        return try {
            UUID.fromString(raw)
        } catch (_: IllegalArgumentException) {
            throw BadRequestResponse("Malformed tenantId")
        }
    }
}

private fun IWorkspace.toResponse() = WorkspaceController.WorkspaceResponse(
    id = id,
    tenantId = tenantId,
    name = name,
    archived = archived,
    createdAt = createdAt.toEpochMilli(),
)
