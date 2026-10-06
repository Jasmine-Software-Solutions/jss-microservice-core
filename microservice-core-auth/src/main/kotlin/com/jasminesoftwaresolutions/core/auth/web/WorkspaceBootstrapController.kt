package com.jasminesoftwaresolutions.core.auth.web

import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.services.SessionAuthService
import com.jasminesoftwaresolutions.core.domain.services.IWorkspaceService
import io.javalin.community.routing.annotations.Endpoints
import io.javalin.community.routing.annotations.Get
import io.javalin.community.routing.annotations.Post
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.NotFoundResponse
import java.util.UUID

/**
 * The **bootstrap** path: the workspace-selection routes a principal can reach
 * before it has a workspace, or any at all.
 *
 * These routes live under `/api/v1/auth/`, which each service's application
 * interface exempts from the auth before-filter, and none of them call
 * `requireAuth()`. That is the whole point: they must work for a principal who
 * has no workspace selected — which is exactly the case `requireAuth()` rejects.
 * They authenticate from the session cookie alone and authorize each action
 * explicitly.
 *
 * Ongoing workspace CRUD — rename, archive, and listing for a principal that
 * already has a workspace — lives in `WorkspaceController` behind `requireAuth()`.
 * Both call the same service. The sign-in routes themselves (`/auth/login`,
 * `/auth/callback`, `/auth/logout`) stay product-side, because the scopes a
 * service asks for are its own.
 */
@Endpoints("/api/v1")
class WorkspaceBootstrapController(
    private val sessionAuthService: SessionAuthService,
    private val workspaceService: IWorkspaceService,
    private val config: WorkspaceAuthConfig,
) {
    // -- Request / response DTOs ------------------------------------------------

    data class SelectWorkspaceRequest(
        val tenantId: String,
        val tenantName: String?,
        val workspaceId: String,
        val workspaceName: String?,
    )

    data class CreateWorkspaceRequest(
        val tenantId: String,
        val name: String?,
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

    // -- Endpoints -------------------------------------------------------------

    /**
     * The workspaces this principal may see *and* open — the picker's set, and an
     * empty array is a valid answer. Archived workspaces are excluded: the picker
     * offers places to open, and an archived workspace is closed. They remain in
     * the management view (`GET /api/v1/workspaces`), which lists archived rows
     * so they can be acted on.
     *
     * An empty list is not an error and not a dead end. The frontend pairs it
     * with [listTenants] to decide between the workspace-management empty state
     * (when the principal can actually create one) and a 404 (when it cannot).
     * Only the frontend can make that call, because only it knows whether a
     * management screen is reachable.
     */
    @Get("/auth/workspaces")
    fun listWorkspaces(ctx: Context) {
        val (principalId, accessToken) = sessionAuthService.authenticateFromCookie(ctx)
        val workspaces = sessionAuthService.resolveListableWorkspaces(principalId, accessToken)

        ctx.json(workspaces.map {
            TenantWorkspaceResponse(it.tenantId, it.tenantName, it.workspaceId, it.workspaceName)
        })
    }

    /**
     * Create a workspace — which may be the principal's **first**, so this route
     * deliberately takes only a tenant.
     *
     * `createWorkspace` is evaluated against `/tenant/{tenantId}` rather than a
     * workspace path because the workspace does not exist yet, which is why the
     * config's `tenantPath` takes a nullable workspace segment.
     */
    @Post("/auth/workspaces")
    fun createWorkspace(ctx: Context) {
        val (principalId, _) = sessionAuthService.authenticateFromCookie(ctx)
        val body = ctx.bodyAsClass(CreateWorkspaceRequest::class.java)
        val tenantId = UUID.fromString(body.tenantId)

        if (!sessionAuthService.evaluate(principalId, config.createWorkspace, config.tenantPath(tenantId, null)))
            throw ForbiddenResponse()

        workspaceService.createWorkspace(
            principalId, tenantId,
            body.name ?: throw BadRequestResponse("Missing name"),
        )
        ctx.status(204)
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
        val (principalId, _) = sessionAuthService.authenticateFromCookie(ctx)
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
     * `SessionAuthService.resolveManageableTenants`.
     */
    @Get("/auth/tenants")
    fun listTenants(ctx: Context) {
        val (principalId, accessToken) = sessionAuthService.authenticateFromCookie(ctx)
        ctx.json(
            sessionAuthService.resolveManageableTenants(principalId, accessToken)
                .map { TenantResponse(it.id, it.name) }
        )
    }
}
