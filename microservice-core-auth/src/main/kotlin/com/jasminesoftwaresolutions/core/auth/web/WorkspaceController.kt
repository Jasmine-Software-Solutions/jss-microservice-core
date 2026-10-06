package com.jasminesoftwaresolutions.core.auth.web

import com.jasminesoftwaresolutions.core.auth.WorkspaceAuthConfig
import com.jasminesoftwaresolutions.core.auth.requireAuth
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
import java.util.UUID

/**
 * Ongoing workspace CRUD, for a principal who already has a workspace selected.
 *
 * This is *not* the bootstrap path. These routes live under `/api/v1/`, so they
 * inherit `requireAuth()`'s requirement that a workspace already be resolved —
 * which is unsatisfiable for a principal with none. Creating the first
 * workspace therefore lives at `POST /api/v1/auth/workspaces`, which is exempt
 * from that requirement (see `WorkspaceBootstrapController`). Both call the
 * same service.
 *
 * The gates below evaluate [WorkspaceAuthConfig]'s actions and paths — product
 * policy data injected at construction, never hardcoded here.
 */
@Endpoints("/api/v1")
class WorkspaceController(
    private val workspaceService: IWorkspaceService,
    private val workspaceRepository: IWorkspaceRepository,
    private val config: WorkspaceAuthConfig,
) {
    data class WorkspaceNameRequest(val name: String?)

    data class WorkspaceResponse(
        val id: UUID,
        val tenantId: UUID,
        val name: String,
        val archived: Boolean,
        val createdAt: Long,
    )

    /**
     * Every workspace in the caller's tenant, archived included — the management
     * view's list.
     *
     * Gated on `listWorkspaces`, which is the tenant-scoped grant, rather than
     * on `getWorkspace` per row. That is deliberate and differs from the
     * picker at `GET /api/v1/auth/workspaces`, which intersects the two: a
     * management screen has to be able to show a workspace in order to act on
     * it, including one the caller could not open.
     */
    @Get("/workspaces")
    fun listWorkspaces(ctx: Context) {
        val auth = ctx.requireAuth()
        if (!auth.evaluate(config.listWorkspaces, config.tenantPath(auth.tenantId, null)))
            throw ForbiddenResponse()
        ctx.json(workspaceRepository.findByTenant(auth.tenantId).map { it.toResponse() })
    }

    /** 201 with the created workspace, so a client need not re-list to learn its id. */
    @Post("/workspaces")
    fun createWorkspace(ctx: Context) {
        val auth = ctx.requireAuth()
        if (!auth.evaluate(config.createWorkspace, config.tenantPath(auth.tenantId, null)))
            throw ForbiddenResponse()
        val body = ctx.bodyAsClass(WorkspaceNameRequest::class.java)
        val created = workspaceService.createWorkspace(
            auth.principalId, auth.tenantId,
            body.name ?: throw BadRequestResponse("Missing name"),
        )
        ctx.status(201).json(created.toResponse())
    }

    @Post("/workspaces/{workspaceId}")
    fun renameWorkspace(ctx: Context, @Param("workspaceId") workspaceId: UUID) {
        val auth = ctx.requireAuth()
        if (!auth.evaluate(config.updateWorkspace, config.tenantPath(auth.tenantId, workspaceId)))
            throw ForbiddenResponse()
        val body = ctx.bodyAsClass(WorkspaceNameRequest::class.java)
        workspaceService.renameWorkspace(
            auth.principalId, auth.tenantId, workspaceId,
            body.name ?: throw BadRequestResponse("Missing name"),
        )
        ctx.status(204)
    }

    @Post("/workspaces/{workspaceId}/archive")
    fun archiveWorkspace(ctx: Context, @Param("workspaceId") workspaceId: UUID) {
        val auth = ctx.requireAuth()
        if (!auth.evaluate(config.deleteWorkspace, config.tenantPath(auth.tenantId, workspaceId)))
            throw ForbiddenResponse()
        workspaceService.archiveWorkspace(auth.principalId, auth.tenantId, workspaceId)
        ctx.status(204)
    }
}

private fun IWorkspace.toResponse() = WorkspaceController.WorkspaceResponse(
    id = id,
    tenantId = tenantId,
    name = name,
    archived = archived,
    createdAt = createdAt.toEpochMilli(),
)
